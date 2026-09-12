# Campus Errand Grab 可靠性验收与压测执行方案

## 1. 目标

本方案用于将项目从“主体功能已经实现”推进到“可验证、可复现、可展示、可写入简历”的完成状态。

后续阶段不再优先增加普通业务接口，而是形成完整证据链：

```text
正确性实现
  -> 自动化测试证明
  -> Docker 环境复现
  -> 故障注入验证
  -> 指标与告警观测
  -> CI 持续执行
  -> 压测原始数据
  -> 报告与简历结论
```

## 2. 当前基线

当前项目已经实现以下主体能力：

- MySQL CAS 抢单和订单状态机。
- AVAILABLE/FROZEN 托管账户与复式账本。
- 结算幂等、请求指纹和资金对账。
- Redis Lua 抢单过滤、限流及故障降级。
- Redis ZSet 延迟索引和数据库扫描兜底。
- 事务 Outbox、租约、重试、DEAD 和管理重放。
- JWT、BCrypt、RBAC 和用户身份上下文。
- Testcontainers、Toxiproxy 和并发集成测试代码。
- Dockerfile、Compose、GitHub Actions、Prometheus、Grafana。
- Python 和 k6 压测入口。

截至 2026-09-11 的本地执行结果：

- Docker Desktop、WSL2 和 Docker Engine 已可用；空 Maven 缓存构建及完整 Testcontainers 测试连续三次通过。
- 当前完整测试每次运行 53 个测试，失败、错误和跳过均为 0；空仓库验证日志及三次稳定性日志保存在 `reports/runtime/`。
- Compose/Flyway/冒烟、四类故障演示、Prometheus/Grafana/告警和脱敏监控快照均有 `reports/` 证据。
- 正式压测矩阵、300 秒持续负载、Redis 故障、结算重试、原始 JSON、图表和正式报告均已生成。
- P0 改动仍是未提交工作区改动；远程 GitHub Actions 全绿记录和分支保护不在本轮执行范围内。

## 3. 执行总览

| 阶段 | 目标 | 建议工期 | 前置依赖 |
| --- | --- | ---: | --- |
| A | 验收并提交 P0 可靠性改动 | 1～2 天 | Docker |
| B | 验证 Compose 一键运行 | 1 天 | 阶段 A |
| C | 自动化故障恢复演示 | 2 天 | 阶段 B |
| D | 完成指标、面板和告警 | 1～2 天 | 阶段 B |
| E | 接入远程仓库和 CI | 0.5～1 天 | 阶段 A |
| F | 执行正式基准测试 | 2～3 天 | 阶段 B、D |
| G | 发布报告并同步简历材料 | 1 天 | 前述全部阶段 |

## 4. 阶段 A：验收 P0 可靠性改动

### A1. 准备 Docker 环境

安装并启动 Docker Desktop，确认 WSL2 或虚拟化后端可用。

```powershell
docker version
docker compose version
docker run --rm hello-world
```

验收条件：

- [x] Docker 客户端和服务端均能返回版本。
- [x] Docker Compose 可用。
- [x] 测试容器能够拉取并运行。

### A2. 执行完整测试

```powershell
mvn -s mvn-settings.xml clean verify
```

重点测试：

- `TimeoutDualSafetyIT`：100 次送达与超时竞态。
- `GrabRedisOutageIT`：Redis 断连、超时和恢复。
- `OutboxReliabilityIT`：多 Worker、租约、重复消费、重试、DEAD 和重放。
- `SecurityWebIT`：JWT、401、403、越权、BCrypt 和管理员权限。

验收条件：

- [x] 所有单元测试通过。
- [x] 所有 MySQL/Redis Testcontainers 测试通过。
- [x] Toxiproxy 故障测试通过。
- [x] Surefire 没有失败、错误或跳过的核心测试。
- [x] JaCoCo 报告能够生成。

### A3. 检查测试稳定性

完整测试至少连续执行三次：

```powershell
1..3 | ForEach-Object {
    mvn -s mvn-settings.xml clean verify
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
```

若出现偶发失败，必须保留失败日志并解决以下常见原因：

- 测试依赖固定等待时间。
- 线程池未关闭。
- Redis 代理未恢复。
- 后台定时任务干扰测试数据。
- 数据库时间边界过窄。
- 多测试共享静态状态。

验收条件：

- [x] 完整测试连续三次通过。
- [x] 并发测试不存在偶发失败。
- [x] 测试结束后没有残留测试线程或容器。

### A4. 审查关键可靠性边界

#### 送达与超时

- [x] 最终状态只允许 `SETTLED` 或 `CANCELLED`。
- [x] 单个订单只能出现 SETTLE 或 CANCEL 中的一种资金路径。
- [x] 发布者 FROZEN 余额最终归零。
- [x] 每轮竞争结束后 5 项对账通过。

#### Redis 故障

- [x] Redis 断开时真实抢单仍然恰好一个赢家。
- [x] Redis 超时不会造成请求长时间阻塞。
- [x] Redis 故障不会泄漏为 HTTP 500。
- [x] Redis 恢复后 marker 和延迟事件能够重建。

#### Outbox

- [x] 领取事件使用独立短事务。
- [x] Redis 网络调用不持有 Outbox 数据库行锁。
- [x] 状态更新校验事件租约。
- [x] 两个 Worker 不会同时持有同一事件。
- [x] PROCESSING 租约过期后能够重新领取。
- [x] 重复消费保持 Redis 派生状态幂等。
- [x] 毒事件不会阻塞同批其他事件。
- [x] 连续失败最终进入 DEAD。
- [x] 管理接口可以重放 DEAD 事件。

#### 安全

- [x] 未登录写请求返回 401。
- [x] 无效、过期或错误签名 JWT 返回 401。
- [x] USER 访问管理接口返回 403。
- [x] 用户不能查看他人钱包或为他人充值。
- [x] 订单身份只能来自认证上下文。
- [x] 注册密码使用 BCrypt，响应不暴露摘要。
- [x] 旧的公开用户创建入口已经关闭。

### A5. 提交 P0 改动

建议拆分为以下提交：

```text
test: strengthen delivery timeout race verification
test: verify redis outage degradation and recovery
refactor: isolate outbox claim and state transactions
test: cover jwt authentication and role boundaries
```

验收条件：

- [ ] 每个提交都能够独立编译。
- [ ] 提交内容与提交信息一致。
- [ ] 工作区没有遗漏的源码和测试修改。
- [ ] 不提交 `target/`、环境密码或临时测试数据。

阶段交付物：

```text
target/surefire-reports/
target/site/jacoco/
清晰的 Git 提交记录
```

## 5. 阶段 B：验证 Docker Compose 一键运行

### B1. 校验和构建

```powershell
docker compose config
docker compose build
docker compose up -d
docker compose ps
```

### B2. 验证服务状态

必须确认：

- [x] MySQL 健康。
- [x] Redis 健康。
- [x] 应用 readiness 成功。
- [x] Prometheus 正常抓取应用。
- [x] Grafana 正常加载 datasource 和 dashboard。
- [x] Flyway 在空数据库上完成所有迁移。

### B3. 执行冒烟流程

```bash
bash scripts/smoke_curl.sh
```

冒烟测试必须覆盖：

- 注册和登录。
- 充值及幂等键。
- 发布订单。
- 并发抢单的单赢家结果。
- 送达和结算。
- 钱包和流水查询。
- 管理员对账。

### B4. 验证重建能力

```powershell
docker compose down
docker compose up -d
```

另外执行一次完全删除测试卷后的空环境启动，但执行前必须确认数据可以丢弃：

```powershell
docker compose down -v
docker compose up -d --build
```

验收条件：

- [x] 首次启动无需手工建库或建表。
- [x] 重启后已有持久化数据仍然可用。
- [x] 空环境重建时 Flyway 正常运行。
- [x] 冒烟脚本退出码为 0。

阶段交付物：

```text
reports/runtime/compose-services.txt
reports/runtime/smoke-result.json
reports/runtime/flyway-migrations.txt
```

## 6. 阶段 C：自动化故障恢复演示

### C1. 新增故障验证脚本

文件名必须直接表达脚本用途：

```text
scripts/verify_redis_outage_recovery.py
scripts/verify_outbox_crash_recovery.py
scripts/verify_duplicate_settlement.py
scripts/verify_reconciliation_drift_detection.py
```

每个脚本必须：

1. 创建独立测试数据。
2. 注入单一故障。
3. 等待或主动触发恢复机制。
4. 对结果进行断言，而不是只打印响应。
5. 执行最终资金对账。
6. 失败时返回非零退出码。
7. 写入机器可读 JSON。
8. 在结束时恢复被修改的环境。

### C2. 场景要求

#### Redis 中断与恢复

- 运行中停止 Redis。
- 并发抢单仍然只有一个赢家。
- 应用 readiness 保持符合降级设计。
- 恢复 Redis 后 marker 和延迟事件重建。

#### Outbox 崩溃恢复

- 制造 PROCESSING 但未完成状态更新的事件。
- 等待租约到期。
- 验证事件被重新领取并最终发布。

#### 重复结算

- 并发触发同一订单结算。
- 验证只有一个幂等记录和一组结算分录。
- 验证账本总和为 0。

#### 对账漂移

- 仅在明确的测试数据库修改账户余额。
- 验证 INV-2 失败并能定位异常。
- 测试结束后恢复数据。

验收条件：

- [x] 四个场景均可用单条命令运行。
- [x] 故障场景失败时脚本退出码非零。
- [x] 结果记录故障时间、恢复时间和最终不变量。
- [x] 完整演示可在 5～10 分钟内完成。

阶段交付物：

```text
reports/failure-tests/redis-outage-*.json
reports/failure-tests/outbox-recovery-*.json
reports/failure-tests/duplicate-settlement-*.json
reports/failure-tests/reconciliation-drift-*.json
```

## 7. 阶段 D：完成可观测性验收

### D1. 补充指标

现有抢单、Outbox 和对账指标基础上，继续补充：

```text
redis_degraded_total
outbox_publish_success_total
outbox_publish_failure_total
outbox_publish_duration_seconds
timeout_queue_lag_seconds
settlement_dead
```

Spring Boot 自带指标应覆盖：

- HTTP 请求耗时。
- JVM 内存和 GC。
- Hikari 连接池使用量。
- 数据库调用相关指标。

不得使用 userId、orderId、幂等键作为指标标签，避免高基数问题。

### D2. 完善 Grafana 面板

至少包含三个主题面板：

1. 抢单链路：请求、赢家、过滤、限流、DB CAS、HTTP 延迟。
2. 异步可靠性：Outbox 积压、DEAD、成功率、耗时和超时队列延迟。
3. 资金安全：对账结果、失败次数、最近成功时间和结算重试。

### D3. 增加告警

至少增加：

- Outbox DEAD 数量大于 0。
- 对账出现失败。
- Outbox 积压持续增长。
- 超时队列处理延迟超过阈值。
- Redis 持续不可用。
- HTTP 5xx 比例超过阈值。

验收条件：

- [x] 所有业务指标能在 Prometheus 查询到。
- [x] Redis 故障时指标能体现降级到 DB CAS。
- [x] 人为制造 DEAD 和账实漂移时告警触发。
- [x] Grafana 图例、单位和时间窗口清晰。

阶段交付物：

```text
ops/prometheus/alerts.yml
reports/monitoring/grab-pipeline.png
reports/monitoring/outbox-and-jobs.png
reports/monitoring/financial-reconciliation.png
```

## 8. 阶段 E：远程仓库与 CI

### E1. 配置远程仓库

- 在 GitHub 或其他平台创建项目仓库。
- 添加 Git remote。
- 推送当前分支和提交历史。

### E2. 验证 CI

CI 必须执行：

```text
mvn clean verify
docker compose config
docker build
上传 Surefire 报告
上传 JaCoCo 报告
```

### E3. 分支保护

- 默认分支禁止直接推送。
- Pull Request 合并前必须通过 CI。
- 测试失败时禁止合并。

验收条件：

- [ ] push 和 Pull Request 均能触发 CI。
- [ ] Testcontainers 在干净 Runner 中通过。
- [ ] Docker 镜像构建通过。
- [ ] 至少保留一次全绿工作流记录。
- [ ] README 中的构建状态来自实际远程工作流。

## 9. 阶段 F：正式基准测试

### F1. 固定测试环境

记录：

- CPU、内存和操作系统。
- Java、MySQL、Redis、Docker 版本。
- JVM 参数。
- Hikari 连接池大小。
- Redis 超时设置。
- 测试工具和脚本版本。
- 是否开启 Redis 前置过滤和限流。

### F2. 测试矩阵

| 场景 | 参数 | 对照 |
| --- | --- | --- |
| 单热点订单 | 500、1000 并发 | Redis 过滤开/关 |
| 多订单竞争 | 1000 单，每单 10、20、50 人 | Redis 过滤开/关 |
| 持续混合负载 | 5～15 分钟 | 不同连接池大小 |
| Redis 故障 | 运行中停止并恢复 | 故障前/中/后 |
| 重复结算 | 多路并发结算 | 正常单次结算 |

每个场景先预热，再正式运行至少 5 次。

### F3. 采集项目

每轮必须记录：

- RPS。
- P50、P95、P99 和最大延迟。
- HTTP 系统错误率。
- 业务拒绝类型和比例。
- Redis 过滤率和限流数量。
- MySQL CAS 数量及冲突量。
- Hikari 连接池占用。
- Outbox 积压、失败量和消费延迟。
- Redis 故障恢复时间。
- 抢单赢家数量。
- 最终 5 项资金不变量。

### F4. 数据目录

```text
reports/benchmarks/environment.md
reports/benchmarks/raw/
reports/benchmarks/charts/
reports/benchmarks/benchmark-report.md
```

验收条件：

- [x] 所有原始结果以 JSON 或 CSV 保存。
- [x] 每个结论可以追溯到场景参数和原始数据。
- [x] Redis 开关使用相同负载进行公平对照。
- [x] 图表由仓库中的原始数据生成。
- [x] Redis 故障期间没有双赢家和资金异常。
- [x] 不记录或使用无法复现的估算数字。

## 10. 阶段 G：报告、文档与简历收尾

### G1. 发布压测报告

`benchmark-report.md` 至少包括：

1. 测试目标和假设。
2. 环境及参数。
3. 场景和预热方式。
4. RPS 与延迟结果。
5. Redis 开关对数据库压力的影响。
6. Redis 故障期间的降级表现。
7. 每轮结束后的资金对账结果。
8. 异常值、瓶颈和适用范围。

### G2. 同步项目文档

- 更新实施路线图中的完成状态。
- 将故障恢复说明更新为自动化脚本入口。
- README 加入实际 CI 状态、监控截图和压测摘要。
- 检查设计文档、代码和测试描述是否一致。
- 将宽泛命名的任务清单内容合并或归档，避免维护两份重复清单。

### G3. 形成简历证据

简历内容必须对应仓库证据：

```text
并发正确性结论 -> 并发集成测试
资金守恒结论   -> 复式账本与对账测试
故障恢复结论   -> Toxiproxy 和故障脚本
性能数字       -> benchmark-report.md 和原始数据
工程化结论     -> Compose、CI 和监控面板
```

验收条件：

- [x] README 中的可靠性结论都有测试对应。
- [x] 简历中的每个数字都能在压测报告中定位。
- [x] 仓库无真实密码、临时数据和构建产物。
- [x] 能在 5～10 分钟内完成一次现场故障演示。

## 11. 完整验收命令

```powershell
mvn -s mvn-settings.xml clean verify
docker compose config
docker compose build
docker compose up -d
docker compose ps
```

```bash
bash scripts/smoke_curl.sh
k6 run load/k6.js
```

```powershell
docker compose down
```

验收后检查：

```text
target/surefire-reports/
target/site/jacoco/
reports/runtime/
reports/failure-tests/
reports/monitoring/
reports/benchmarks/
```

## 12. 项目最终完成定义

同时满足以下条件后，才能判定本方案全部完成：

- [x] 完整 Testcontainers 测试连续三次通过。
- [ ] P0 可靠性改动已经提交，工作区干净。
- [x] `docker compose up --build` 可在新环境一键启动。
- [x] 四类故障验证脚本全部通过。
- [x] Prometheus 指标、Grafana 面板和告警均经过实际验证。
- [ ] 远程 CI 在干净 Runner 中全绿。
- [x] 正式压测至少覆盖热点、多订单、持续负载和 Redis 故障。
- [x] 仓库包含原始压测数据、图表和正式报告。
- [x] README、设计文档、测试与实现一致。
- [x] 简历只引用可复现、可追溯的项目结果。

## 13. 立即执行清单

- [x] 安装并启动 Docker Desktop。
- [x] 执行一次 `mvn clean verify`，保存首次失败信息。
- [x] 修复失败项并连续运行三次。
- [ ] 审查并提交当前 P0 工作区改动。
- [x] 启动完整 Compose 环境并执行冒烟测试。
- [x] 完成故障验证脚本。
- [x] 完成指标、Grafana 和告警验收。
- [ ] 推送远程仓库并验证 CI。
- [x] 执行正式压测并生成报告。
- [x] 同步 README、路线图和简历材料。

本方案的本地可执行部分已完成；未勾选项明确属于 Git 提交、推送和远程 CI，不在本轮操作范围内。
