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

当前不能判定项目全部完成，主要原因是：

- 本机没有 Docker，尚未执行完整 `mvn verify` 和 Compose 验收。
- Redis 故障测试没有接入真实抢单业务流程。
- Outbox 没有覆盖多实例、重复消费和崩溃恢复。
- Web 安全边界没有自动化接口测试。
- 送达与超时竞态测试断言过弱。
- Grafana、告警和故障恢复没有实际运行证据。
- `reports/` 中没有正式压测数据和结论。
- Git 仓库没有 remote，CI 没有实际运行记录。

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
| P1 | N8 接入远程仓库并验证 CI | N1～N4 | 每次提交自动验证 |
| P2 | N9 完成基准测试 | N5、N6 | 得到可信性能数据 |
| P2 | N10 发布压测报告 | N9 | 简历数字有原始证据 |
| P2 | N11 同步项目文档 | 全部 | 文档与代码一致 |

必须先完成 P0，再开展正式压测。存在正确性或测试可信度缺口时，性能数字没有简历价值。

## 4. P0：补齐正确性与可靠性验证

### N1. 修正送达与超时竞态测试

**现状问题**

`TimeoutDualSafetyIT.deliveryAndTimeoutProduceOneTerminalPath` 当前接受 `DELIVERED`，但 `DELIVERED` 不是终态；测试还在竞争开始前将截止时间改为已过期，因此没有覆盖截止时间边界附近的真实竞争。

**实施内容**

- 将最终状态限制为 `SETTLED` 或 `CANCELLED`。
- 明确断言只有一种资金路径发生：结算或退款。
- 检查 FREEZE、SETTLE、CANCEL 分录数量。
- 检查发布者 FROZEN 余额最终为 0。
- 每轮结束执行 `ReconService.run()`，要求 5 项不变量全部通过。
- 将竞态场景重复执行至少 100 次，避免测试只在单次调度下偶然通过。
- 增加两个明确边界用例：截止前送达成功、截止后送达返回 `EXPIRED`。

**验收标准**

- [ ] 测试不再接受 `DELIVERED` 作为最终结果。
- [ ] 100 次竞态运行无双退款、双结算或资金冻结残留。
- [ ] 每次竞态结束后对账通过。
- [ ] 测试在本地和 CI 中稳定通过。

### N2. 将 Redis 故障注入接入真实业务

**现状问题**

当前 Toxiproxy 测试只验证 `StringRedisTemplate` 能感知断连及恢复，没有验证 `GrabService` 在相同故障下是否降级到 MySQL。

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

- [ ] 断连期间真实业务仍恰好一个赢家。
- [ ] Redis 故障不会产生 500 或长时间阻塞。
- [ ] Redis 恢复后派生数据能够自动重建。
- [ ] 故障前后资金不变量全部成立。

### N3. 完成 Outbox 多实例与崩溃恢复

**现状问题**

当前测试只确认业务事务写入了 Outbox 记录，以及 DEAD 状态可以被 SQL 改回 PENDING；没有真正验证 Worker、多实例争抢、重复投递和进程崩溃恢复。

当前 `OutboxWorker.publish()` 在一个数据库事务内领取整批事件并调用 Redis。外部 Redis 调用可能延长数据库事务和行锁持有时间，需要缩短领取事务。

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

- [ ] Redis 网络调用不发生在持有 Outbox 行锁的长事务内。
- [ ] 多实例不会同时拥有同一事件的有效租约。
- [ ] 重复消费保持幂等。
- [ ] 崩溃后的 PROCESSING 事件能够自动恢复。
- [ ] DEAD 查询、告警和管理重放均可用。

### N4. 补齐 JWT 与 RBAC Web 集成测试

**现状问题**

安全代码已经存在，但当前没有 MockMvc 或随机端口 HTTP 测试证明实际路由受到保护。

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

- [ ] 身份冒用场景全部被自动化测试拒绝。
- [ ] ADMIN 和 USER 权限矩阵清晰且测试通过。
- [ ] 不存在可绕过认证创建不可登录业务账户的公开接口。
- [ ] 安全测试进入 `mvn verify` 和 CI。

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

- [ ] 新机器只需 Docker 和环境变量即可运行。
- [ ] `docker compose up --build` 无需手工建库。
- [ ] 冒烟脚本退出码为 0。
- [ ] Compose 验收步骤记录在 README。

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

- [ ] 三类面板齐全：抢单链路、异步任务、资金对账。
- [ ] Redis 故障期间能够看到降级和 DB CAS 指标变化。
- [ ] 人为制造 DEAD 事件和对账失败时告警触发。
- [ ] 仓库中保存可复现步骤和脱敏截图。

### N7. 将故障演示自动化

**实施内容**

在 `scripts/` 下增加可重复执行的故障演示入口，至少覆盖：

```text
failure_redis_outage
failure_outbox_recovery
failure_duplicate_settlement
failure_recon_drift
```

每个脚本应：

1. 创建独立测试数据。
2. 注入单一故障。
3. 等待或触发恢复机制。
4. 执行断言，而不只是打印响应。
5. 执行最终对账。
6. 输出机器可读 JSON 到 `reports/failures/`。
7. 恢复被修改的环境。

禁止对生产数据库运行账实漂移脚本。脚本必须要求显式的测试环境标志。

**验收标准**

- [ ] 四个场景均可通过单条命令运行。
- [ ] 失败时脚本返回非零退出码。
- [ ] 结果包含故障时间、恢复时间和不变量状态。
- [ ] 故障恢复演示可在 5～10 分钟内完成。

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

- [ ] 原始 JSON 全部写入 `reports/benchmarks/raw/`。
- [ ] 每个结论都能追溯到测试参数和原始数据。
- [ ] Redis 开关使用相同业务负载进行对照。
- [ ] 故障期间没有双赢家和资金异常。

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

- [ ] 不填写估算或无法复现的数字。
- [ ] 图表由仓库中的原始数据生成。
- [ ] 报告明确区分业务拒绝与系统错误。
- [ ] 简历使用的每个数字均能在报告中找到。

### N11. 同步 README、路线图和简历表述

完成所有验收后：

- 更新 `docs/implementation-roadmap.md` 中的复选框。
- 在 README 中加入实际 CI 状态、运行方式、监控入口和压测摘要。
- 更新 `docs/failure-recovery.md`，由手工说明升级为脚本入口和预期断言。
- 检查 README、设计文档、代码和测试之间不存在不一致描述。
- 使用正式压测报告中的数字填写简历，不使用“高并发”“高性能”等无数字表述。

**验收标准**

- [ ] 路线图每个完成项都有代码或报告证据。
- [ ] README 的每个可靠性结论都有测试对应。
- [ ] 简历数字与压测报告一致。
- [ ] 项目可以在面试中现场完成一次故障降级演示。

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
reports/failures/
reports/monitoring/
reports/benchmarks/
```

## 8. 下一批立即执行清单

建议下一次开发只处理以下四项，不同时开始压测：

- [ ] N1：收紧送达与超时竞态测试，并循环执行 100 次。
- [ ] N2：将 Toxiproxy 断连和超时接入真实抢单业务。
- [ ] N3：拆分 Outbox 短领取事务，补多实例和崩溃恢复测试。
- [ ] N4：新增 JWT/RBAC Web 集成测试。

完成后统一执行 `mvn clean verify`。只有 P0 全部通过，才能进入 Compose、监控和正式压测阶段。
