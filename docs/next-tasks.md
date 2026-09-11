# Campus Errand Grab 接下来要做的任务

## 1. 目标

本文只记录当前尚未完成或尚未满足验收条件的工作。已经完成的基础功能不再重复规划。

下一阶段的核心目标不是继续增加业务接口，而是形成以下可验证证据链：

```text
代码实现
  -> 自动化测试证明并发与故障边界
  -> Docker 环境可复现
  -> CI 持续验证
  -> 指标和故障演示可观察
  -> 压测原始数据支持简历数字
```

## 2. 当前状态

当前已经具备：

- MySQL CAS 抢单和资金事务。
- 送达截止时间守卫及送达延迟事件。
- Redis Lua 前置过滤和抢单限流。
- 复式账本、资金对账、结算幂等及请求指纹。
- MINT、PLATFORM 系统账户隔离。
- JWT、BCrypt 和 RBAC 的主体实现。
- Testcontainers、Toxiproxy、事务 Outbox 的主体实现。
- Dockerfile、Compose、GitHub Actions、Prometheus 和 Grafana 配置。
- k6 和 Python 压测入口。

本轮本地验收已经完成：

- Docker Desktop、WSL2 和 Docker Engine 可用；空 Maven 缓存构建和完整测试已连续三次通过，每次 47 个测试、0 失败、0 错误、0 跳过。
- Compose、Flyway、冒烟流程和 readiness/liveness 证据已写入 `reports/runtime/`。
- Redis、Outbox、重复结算和对账漂移四类故障脚本均有成功报告，索引见 `reports/failure-tests/index.md`。
- Prometheus 指标、Grafana 数据源/面板、DEAD/对账/队列告警均已实际验证并恢复；证据见 `reports/monitoring/`。
- 正式压测矩阵、300 秒持续负载、Redis 故障和结算重试均已完成；正式报告状态为 `COMPLETE`。
- 仍未完成的只有本轮明确不处理的 Git 提交/推送和远程 CI 全绿记录。

## 3. 优先级与执行顺序

| 优先级 | 任务 | 依赖 | 预期结果 |
| --- | --- | --- | --- |
| P0 | N1 修正竞态测试 | 无 | 正确性断言可信 |
| P0 | N2 完成业务级 Redis 故障测试 | Docker | 证明 Redis 故障可降级 |
| P0 | N3 完成 Outbox 可靠性测试与事务改造 | Docker | 证明多实例和崩溃恢复 |
| P0 | N4 完成 Web 安全测试 | Docker | 证明身份和权限边界 |
| P1 | N5 验证 Compose 一键启动 | Docker | 新环境可复现 |
| P1 | N6 完成可观测性验收 | N5 | 指标、面板和告警可用 |
| P1 | N7 自动化故障演示 | N2、N3、N5 | 可靠性结论可重复演示 |
| P1 | N8 接入远程仓库并验证 CI | N1～N4 | 后续 Git 工作，当前不执行 |
| P2 | N9 完成基准测试 | N5、N6 | 已完成，见 `reports/benchmarks/raw/` |
| P2 | N10 发布压测报告 | N9 | 已完成，见 `reports/benchmarks/benchmark-report.md` |
| P2 | N11 同步项目文档 | 全部 | 已完成，本文件与 README 已同步 |

必须先完成 P0，再开展正式压测。存在正确性或测试可信度缺口时，性能数字没有简历价值。

## 4. P0：补齐正确性与可靠性验证

### N1. 修正送达与超时竞态测试

**历史缺口（已完成）**

`TimeoutDualSafetyIT` 已将最终状态限制为 `SETTLED` 或 `CANCELLED`，补齐截止时间守卫、资金路径和对账断言，并在完整测试中连续三次通过。

**实施内容**

- 将最终状态限制为 `SETTLED` 或 `CANCELLED`。
- 明确断言只有一种资金路径发生：结算或退款。
- 检查 FREEZE、SETTLE、CANCEL 分录数量。
- 检查发布者 FROZEN 余额最终为 0。
- 每轮结束执行 `ReconService.run()`，要求 5 项不变量全部通过。
- 将竞态场景重复执行至少 100 次，避免测试只在单次调度下偶然通过。
- 增加两个明确边界用例：截止前送达成功、截止后送达返回 `EXPIRED`。

**验收标准**

- [x] 测试不再接受 `DELIVERED` 作为最终结果。
- [x] 100 次竞态运行无双退款、双结算或资金冻结残留。
- [x] 每次竞态结束后对账通过。
- [x] 测试在本地稳定通过；远程 CI 留待 Git 推送后验证。

### N2. 将 Redis 故障注入接入真实业务

**历史缺口（已完成）**

`GrabRedisOutageIT` 已覆盖真实业务断连、数据库 CAS 单赢家、恢复重建和资金对账；Compose Redis 停止/恢复脚本也已通过。

**实施内容**

- 让 Spring 测试上下文通过 Toxiproxy 连接 Redis。
- 在 Redis 正常时创建订单并确认 marker 存在。
- 切断 Toxiproxy 连接后并发调用真实 `GrabService.grab`。
- 断言只有一个赢家，其他请求被数据库 CAS 拒绝。
- 检查订单只有一个 `taker_id`。
- 完成订单并执行资金对账。
- 恢复 Redis 后运行 Outbox Worker 或 marker 重建任务。
- 验证 marker、延迟事件及后续新订单恢复正常。
- 增加 Redis 高延迟超过客户端超时的场景，确认请求能在合理时间内降级。

**建议测试名称**

```text
GrabRedisOutageIT.redisDisconnectStillProducesExactlyOneWinner
GrabRedisOutageIT.redisTimeoutFailsOpenWithinConfiguredDeadline
GrabRedisOutageIT.redisRecoveryRebuildsDerivedState
```

**验收标准**

- [x] 断连期间真实业务仍恰好一个赢家。
- [x] Redis 故障不会产生 500 或长时间阻塞。
- [x] Redis 恢复后派生数据能够自动重建。
- [x] 故障前后资金不变量全部成立。

### N3. 完成 Outbox 多实例与崩溃恢复

**历史缺口（已完成）**

Outbox 领取、租约、重试、DEAD、重放、重复消费和崩溃恢复测试已纳入 `mvn verify`；领取事务与 Redis 副作用已拆开，完整测试连续三次通过。

**建议改造**

将处理过程拆成三个阶段：

1. `OutboxClaimService.claimBatch()` 在短事务中使用 `FOR UPDATE SKIP LOCKED`，写入 `PROCESSING` 和租约后立即提交。
2. Worker 在数据库事务之外执行 Redis 幂等操作。
3. 使用独立短事务将事件标记为 `PUBLISHED`、`RETRY` 或 `DEAD`。

不要通过同类内部调用依赖 Spring 事务代理。领取事务应放在独立 Bean 中，或显式使用 `TransactionTemplate`。

**必须新增的测试**

- 两个 Worker 同时领取同一批事件，每个事件只能被一个 Worker 获得。
- Redis 操作成功但状态更新前发生异常，租约过期后事件可以被再次消费。
- 同一事件消费两次，Redis 最终状态不重复、不损坏。
- `PROCESSING` 事件租约过期后可被重新领取。
- 连续失败执行指数退避，达到上限进入 `DEAD`。
- 通过管理接口重放 DEAD 事件，最终进入 `PUBLISHED`。
- 业务事务回滚时 Outbox 事件也必须回滚。
- 单条毒事件不能阻塞同批后续事件。

**验收标准**

- [x] Redis 网络调用不发生在持有 Outbox 行锁的长事务内。
- [x] 多实例不会同时拥有同一事件的有效租约。
- [x] 重复消费保持幂等。
- [x] 崩溃后的 PROCESSING 事件能够自动恢复。
- [x] DEAD 查询、告警和管理重放均可用。

### N4. 补齐 JWT 与 RBAC Web 集成测试

**历史缺口（已完成）**

JWT/RBAC Web 集成测试已覆盖未登录、无效令牌、越权、钱包隔离、管理员权限和 BCrypt 存储，已进入完整测试流程。

**实施内容**

使用 `MockMvc` 或 `WebTestClient` 覆盖完整安全边界：

- 未登录访问订单写接口返回 401。
- 无效、过期、签名错误 JWT 返回 401。
- 普通用户访问 `/api/admin/**` 返回 403。
- 用户不能查看其他人的钱包。
- 用户不能以其他用户身份发布、抢单、取消或送达。
- 普通用户不能调用指定用户充值接口。
- ADMIN 可以调用管理接口和管理充值接口。
- 注册密码以 BCrypt 摘要存储，响应中永不返回摘要。
- 登录成功返回可用 JWT，后续请求能解析为正确用户。

**额外检查**

- 评估是否应公开 `/actuator/prometheus`。生产环境通常应限制为监控网络或独立管理端口。
- 评估是否保留公开的 `POST /api/users` 兼容接口；若注册已经由 `/api/auth/register` 承担，建议删除或仅在测试 profile 开启。

**验收标准**

- [x] 身份冒用场景全部被自动化测试拒绝。
- [x] ADMIN 和 USER 权限矩阵清晰且测试通过。
- [x] 不存在可绕过认证创建不可登录业务账户的公开接口。
- [x] 安全测试进入 `mvn verify`；远程 CI 留待 Git 推送后验证。

## 5. P1：完成运行环境、监控和故障演示

### N5. 验证 Docker Compose 一键启动

**环境前置条件**

安装并启动 Docker Desktop，确保以下命令成功：

```bash
docker version
docker compose version
```

**验证步骤**

```bash
docker compose config
docker compose build
docker compose up -d
docker compose ps
bash scripts/smoke_curl.sh
docker compose down
```

**必须验证**

- MySQL 和 Redis 健康后应用才启动。
- Flyway 在空数据库上完成全部迁移。
- 应用 readiness 返回成功。
- 冒烟流程完成注册、登录、充值、发布、抢单、送达、结算和对账。
- Prometheus 能抓取应用指标。
- Grafana 自动加载 datasource 和 dashboard。
- 删除容器并重新创建时，初始化仍然稳定。
- Redis 停止时应用 readiness 策略符合“Redis 可降级”的设计。

**验收标准**

- [x] 新机器只需 Docker 和环境变量即可运行。
- [x] `docker compose up --build` 无需手工建库。
- [x] 冒烟脚本退出码为 0。
- [x] Compose 验收步骤记录在 README。

### N6. 完成指标、面板和告警验收

**实施内容**

- 运行包含抢单成功、Redis 过滤、限流、结算重试、Outbox 积压和对账失败的测试流量。
- 验证 Prometheus 中存在并持续更新以下指标：

```text
grab_attempt_total
grab_redis_filtered_total
grab_db_cas_total
grab_winner_total
settlement_retry_total
outbox_pending
outbox_dead
timeout_queue_lag_seconds
recon_failure_total
recon_last_success_timestamp
```

- 为 Grafana 面板补充合理单位、图例、时间窗口和阈值。
- 为 `outbox_dead > 0`、对账失败、超时队列延迟过高增加告警规则。
- 确认标签不包含 userId、orderId 等高基数字段。
- 将面板截图保存到 `reports/monitoring/`。

**验收标准**

- [x] 三类面板齐全：抢单链路、异步任务、资金对账。
- [x] Redis 故障期间能够看到降级和 DB CAS 指标变化。
- [x] 人为制造 DEAD 事件和对账失败时告警触发。
- [x] 仓库中保存可复现步骤和脱敏截图。

### N7. 将故障演示自动化

**实施内容**

在 `scripts/` 下增加可重复执行的故障演示入口，至少覆盖：

```text
scripts/verify_redis_outage_recovery.py
scripts/verify_outbox_crash_recovery.py
scripts/verify_duplicate_settlement.py
scripts/verify_reconciliation_drift_detection.py
```

每个脚本应：

1. 创建独立测试数据。
2. 注入单一故障。
3. 等待或触发恢复机制。
4. 执行断言，而不只是打印响应。
5. 执行最终对账。
6. 输出机器可读 JSON 到 `reports/failure-tests/`。
7. 恢复被修改的环境。

禁止对生产数据库运行账实漂移脚本。脚本必须要求显式的测试环境标志。

**验收标准**

- [x] 四个场景均可通过单条命令运行。
- [x] 失败时脚本返回非零退出码。
- [x] 结果包含故障时间、恢复时间和不变量状态。
- [x] 故障恢复演示可在 5～10 分钟内完成。

### N8. 接入远程仓库并验证 CI

**实施内容**

- 在 GitHub 或其他代码托管平台创建仓库。
- 配置 Git remote 并推送当前提交。
- 确认 Actions Runner 支持 Docker/Testcontainers。
- 执行完整 `mvn verify`、Compose 配置校验和 Docker 镜像构建。
- 上传 Surefire 与 JaCoCo 报告。
- 为默认分支启用合并保护，要求 CI 成功。

**验收标准**

- [ ] push 和 Pull Request 都能触发 CI。
- [ ] 完整测试在干净 Runner 中通过。
- [ ] 失败测试能够阻止合并。
- [ ] README 中的构建状态来自实际远程工作流。

## 6. P2：产出正式性能证据

### N9. 执行可复现基准测试

**测试前准备**

- 固定 CPU、内存、JDK、JVM 参数、MySQL、Redis 和连接池配置。
- 关闭会污染结果的后台软件。
- 清理或使用独立压测数据库。
- 每个场景先预热，再正式采集。
- 每个场景至少运行 5 次。

**测试矩阵**

| 场景 | 参数 | 对照组 |
| --- | --- | --- |
| 单热点订单 | 500、1000 并发 | Redis 过滤开/关 |
| 多订单竞争 | 1000 订单，每单 10、20、50 人 | Redis 过滤开/关 |
| 混合持续负载 | 5～15 分钟 | 不同连接池大小 |
| Redis 故障 | 运行中停止并恢复 Redis | 故障前/中/后 |
| 结算重试 | 重复及并发投递 | 单次正常结算 |

**每轮必须采集**

- 吞吐量 RPS。
- P50、P95、P99、最大延迟。
- HTTP 错误率及业务错误分布。
- Redis 过滤率和限流量。
- MySQL CAS 请求数、冲突数和连接池占用。
- Outbox 积压和消费延迟。
- Redis 故障恢复时间。
- 抢单赢家数量。
- 最终 5 项对账结果。

**验收标准**

- [x] 原始 JSON 全部写入 `reports/benchmarks/raw/`。
- [x] 每个结论都能追溯到测试参数和原始数据。
- [x] Redis 开关使用相同业务负载进行对照。
- [x] 故障期间没有双赢家和资金异常。

### N10. 生成正式压测报告

新增：

```text
reports/benchmarks/benchmark-report.md
reports/benchmarks/environment.md
reports/benchmarks/raw/*.json
reports/benchmarks/charts/*
```

报告结构：

1. 测试目标和被验证的假设。
2. 硬件、软件和运行参数。
3. 场景、预热方式和执行次数。
4. 原始数据位置。
5. RPS 与延迟结果。
6. Redis 过滤开关对数据库压力的影响。
7. Redis 故障期间的降级表现。
8. 每轮测试后的资金不变量。
9. 已知瓶颈、异常值和结果适用范围。

**验收标准**

- [x] 不填写估算或无法复现的数字。
- [x] 图表由仓库中的原始数据生成。
- [x] 报告明确区分业务拒绝与系统错误。
- [x] 简历使用的每个数字均能在报告中找到。

### N11. 同步 README、路线图和简历表述

完成所有验收后：

- 更新 `docs/implementation-roadmap.md` 中的复选框。
- 在 README 中加入实际 CI 状态、运行方式、监控入口和压测摘要。
- 更新 `docs/failure-recovery.md`，由手工说明升级为脚本入口和预期断言。
- 检查 README、设计文档、代码和测试之间不存在不一致描述。
- 使用正式压测报告中的数字填写简历，不使用“高并发”“高性能”等无数字表述。

**验收标准**

- [x] 路线图每个完成项都有代码或报告证据。
- [x] README 的每个可靠性结论都有测试对应。
- [x] 简历数字与压测报告一致。
- [x] 项目可以在面试中现场完成一次故障降级演示。

## 7. 完整验证命令

Docker 可用后执行：

```bash
mvn -s mvn-settings.xml clean verify
docker compose config
docker compose build
docker compose up -d
bash scripts/smoke_curl.sh
k6 run load/k6.js
docker compose down
```

验证结束后应检查：

```text
target/surefire-reports/
target/site/jacoco/
reports/failure-tests/
reports/monitoring/
reports/benchmarks/
```

## 8. Docker 可用后的下一批执行清单

建议下一次开发只处理以下四项，不同时开始压测：

- [ ] N1：在 Docker 环境中连续运行收紧后的送达与超时竞态测试。
- [ ] N2：在 Docker 环境中运行已接入真实业务的 Toxiproxy 断连和超时测试。
- [ ] N3：在 Docker 环境中运行已拆分事务的 Outbox 多实例和崩溃恢复测试。
- [ ] N4：在 Docker 环境中运行已加入的 JWT/RBAC Web 集成测试。

完成后统一执行 `mvn clean verify`。只有 P0 全部通过，才能进入 Compose、监控和正式压测阶段。
