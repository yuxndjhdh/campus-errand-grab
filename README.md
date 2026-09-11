# Campus Errand Grab

基于 Java 21、Spring Boot、MySQL 和 Redis 的校园跑腿抢单单体服务。MySQL 是订单状态与资金的唯一事实来源；Redis 只做抢单前置过滤、限流和延迟索引，Redis 故障时业务降级到数据库 CAS。所有资金变化都在事务中写入复式账本，异步 Redis 写入通过事务 Outbox 恢复。

## 技术栈

- Java 21、Spring Boot 3.3.6、JdbcTemplate、MySQL 8.4、Redis 7、Flyway
- Spring Security、BCrypt、HMAC-SHA256 JWT、RBAC
- Micrometer、Actuator、Prometheus、Grafana
- JUnit 5、Testcontainers、MySQL/Redis 真实集成测试
- Python `requests` 压测冒烟脚本，`load/k6.js` 正式压测场景

## 快速启动

复制 `.env.example` 为 `.env` 并填写本地配置，然后执行：

```bash
docker compose up --build
bash scripts/smoke_curl.sh
```

Python 冒烟、故障脚本和基准执行器依赖 `requests` 与 `PyMySQL`：

```bash
python -m pip install -r requirements.txt
```

Compose 会等待 MySQL 和 Redis 健康后启动应用，Flyway 自动迁移数据库。应用健康检查：

```text
http://127.0.0.1:8080/actuator/health/readiness
http://127.0.0.1:8080/actuator/prometheus
http://127.0.0.1:3000
```

不使用 Compose 时，运行 `mvn -s mvn-settings.xml verify`；集成测试会自动启动 MySQL 和 Redis Testcontainers，因此不读取本机固定端口。需要本机安装并运行 Docker。

## API 流程

1. `POST /api/auth/register` 注册，`POST /api/auth/login` 获取 JWT。
2. 使用 JWT 调用 `POST /api/users/me/recharge` 模拟支付回调，必须携带幂等键。
3. 发布者调用 `POST /api/orders`，请求体只包含标题、详情、悬赏和截止时间。
4. 跑腿人调用 `POST /api/orders/{id}/grab`、`POST /api/orders/{id}/deliver`。
5. 发布者调用 `POST /api/orders/{id}/cancel`；管理员调用 `/api/admin/recon/run` 和 `/api/admin/stats`。

请求身份来自 JWT，不信任请求体中的 `publisherId` 或 `userId`。管理员账号由 `ADMIN_USERNAME` 和 `ADMIN_PASSWORD` 在应用启动时幂等创建；普通用户不能访问管理接口或为其他用户充值。

## 正确性设计

- 抢单使用 MySQL 条件更新：`status='PUBLISHED'`、未过截止时间、发布者不能自抢，影响行数为 1 才算成功。
- Lua marker 携带截止时间和发布者 ID；自抢不会消耗 marker，已消费 marker 直接返回统一冲突，不再查询订单；marker 缺失或 Redis 不可用时回退到 MySQL CAS。
- 送达 SQL 同时校验 `deliver_deadline_at > NOW(3)`，送达与超时竞争只能产生一个合法终态。
- 发单、抢单、取消/超时和结算在同一事务中写 `t_outbox_event`；Worker 使用 `SKIP LOCKED`、指数退避和 `DEAD` 状态重建 Redis 延迟索引。
- `t_idempotent_op` 保存 SHA-256 请求指纹和响应；相同 key 的不同参数返回 `409 IDEMPOTENCY_CONFLICT`。
- 普通订单只允许 `USER` 角色；`MINT` 只发行资金，`PLATFORM` 只接收佣金。结算按 `(userId, accountType)` 聚合分录。
- 对账验证 INV-1 到 INV-5：账本守恒、物化余额、托管金额、负余额和过期开放订单。

## 测试与压测

```bash
mvn -s mvn-settings.xml -Dtest=CommissionMathTest,StateMachineTableTest test
mvn -s mvn-settings.xml verify

python scripts/load_test.py --phase burst --clients 500
python scripts/load_test.py --phase mixed --orders 100 --clients-per-order 20
k6 run load/k6.js
```

正式 HTTP 基准可使用重复执行器；它要求显式的 disposable test environment，默认每个变体预热一次并正式运行五次。当前验收使用 200 个轮换身份、200 个工作线程、Hikari 连接池 20、关闭限流、抢单 TTL 3600 秒：

```bash
python scripts/collect_benchmark_environment.py --formal-status COMPLETE --runs 5 --warmup-runs 1 --prefilter-modes on,off --pool-sizes 20 --takers 200 --workers 200 --claim-ttl-seconds 3600 --rate-limit-enabled false
python scripts/run_benchmarks.py --test-environment --scenario all --runs 5 --warmup-runs 1 --prefilter-modes on,off --pool-sizes 20 --takers 200 --workers 200
python scripts/load_test.py --phase sustained --duration-seconds 300 --clients-per-order 20 --takers 200 --workers 200 --output reports/benchmarks/raw/sustained-300s-on-pool20.json
python scripts/benchmark_redis_fault.py --test-environment --clients 200 --output reports/benchmarks/raw/redis-fault-200-on-pool20.json
python scripts/benchmark_settlement_retry.py --test-environment --output reports/benchmarks/raw/settlement-retry-pool20.json
python scripts/generate_benchmark_report.py
```

`--takers` 是轮换使用的认证身份数量。若要避免每用户限流影响抢单基线，应按并发规模提供足够身份，或在报告中明确记录限流结果。执行器把每轮 JSON 和索引写入 `reports/benchmarks/raw/`；正式持续负载、Redis 故障和结算重试证据也写入该目录。正式结果、图表和环境参数见 [reports/benchmarks/benchmark-report.md](reports/benchmarks/benchmark-report.md)。

故障演示入口和断言见 [docs/failure-recovery.md](docs/failure-recovery.md)。四个脚本的结果写入 `reports/failure-tests/`，失败返回非零退出码，成功报告索引见 [reports/failure-tests/index.md](reports/failure-tests/index.md)。Prometheus 告警规则位于 `ops/prometheus/alerts.yml`，Grafana dashboard 位于 `ops/grafana/dashboards/campus-errand.json`；监控验证和脱敏指标快照见 [reports/monitoring/monitoring-validation.json](reports/monitoring/monitoring-validation.json)。

压测脚本会把原始 JSON 保存到 `reports/` 或 `reports/benchmarks/raw/`，其中的性能数字只来自实际运行结果。当前本地正式报告状态为 `COMPLETE`：10 个热点/多订单变体各完成 1 次预热和 5 次正式运行，并补充了 300 秒持续负载、Redis 故障和结算重试证据。结果适用范围、异常样本和原始文件索引见 [reports/benchmarks/benchmark-report.md](reports/benchmarks/benchmark-report.md)；这些数字不表示系统的普遍容量上限。

设计细节见 [docs/design.md](docs/design.md)，故障注入流程见 [docs/failure-recovery.md](docs/failure-recovery.md)，面试说明见 [docs/interview-notes.md](docs/interview-notes.md)。
