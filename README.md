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

压测脚本会把原始 JSON 保存到 `reports/`，其中的性能数字只应来自实际运行结果。正式报告应同时记录 P50/P95/P99、错误类型、Redis 过滤率、DB CAS 数、连接池、Redis 故障窗口和对账结果；项目不在 README 中预填估算值。

设计细节见 [docs/design.md](docs/design.md)，故障注入流程见 [docs/failure-recovery.md](docs/failure-recovery.md)，面试说明见 [docs/interview-notes.md](docs/interview-notes.md)。
