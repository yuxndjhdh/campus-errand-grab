# Campus Errand Grab 后续实施路线图

## 1. 文档目标

本文用于指导项目从“并发与资金一致性样例”演进为可写入 Java 后端简历、可复现、可压测、可观测的完整工程项目。

项目后续建设应始终遵守以下原则：

1. MySQL 是订单状态与资金数据的唯一事实来源。
2. Redis 只负责流量过滤、延迟索引和性能优化，Redis 故障不能破坏正确性。
3. 所有资金变化必须处于数据库事务中，并写入复式账本。
4. 所有异步操作都必须允许重复执行，并具备失败恢复能力。
5. README 中的性能与可靠性结论必须有测试、指标或原始报告支撑。

## 2. 当前基础

项目目前已经具备以下能力：

- Java 21、Spring Boot 3.3、JdbcTemplate、MySQL 8、Redis、Flyway。
- 使用 MySQL 条件更新保证并发抢单只有一个赢家。
- 使用 Redis Lua 对热点抢单流量进行前置过滤。
- 使用 AVAILABLE/FROZEN 账户和复式分录处理资金托管、退款与结算。
- 使用幂等键、订单状态 CAS 和账本唯一约束防止重复结算。
- 使用 Redis ZSet 与数据库扫描处理订单超时。
- 实现 5 项资金及状态不变量对账。
- 已有 13 个通过的单元及真实 MySQL/Redis 集成测试。

当前主要不足：

- 抢单成功后未写入送达超时 ZSet，双通道超时机制没有完全闭环。
- Redis 过滤失败后仍查询数据库，尚未真正减少数据库总请求数。
- Redis 故障测试只覆盖 `FLUSHDB`，没有模拟断连和超时。
- 送达 SQL 没有校验送达截止时间。
- 系统账户参与普通业务时可能导致结算异常。
- 客户端可直接传入任意用户 ID，缺少身份认证和权限控制。
- 数据库密码以明文写入配置和脚本。
- 缺少 Docker Compose、Testcontainers、CI 和正式压测报告。
- 后台任务吞掉异常，缺少日志、指标和告警。

## 3. 目标架构

```text
HTTP API + JWT/RBAC
        |
    GrabFacade
        |-- Redis Lua：限流与前置过滤，可降级
        `-- OrderTxService：MySQL CAS + Outbox，同一事务
                                  |
                             Outbox Worker
                                  |
                    Redis 延迟索引或其他事件消费者
                                  |
Timeout Worker --------------> MySQL 状态 CAS
                                  |
                       复式账本 + 对账 + 告警
```

不建议为了展示技术栈而提前拆分微服务。单体内部的事务、一致性、可靠事件和可观测性问题解决完整后，再评估服务拆分。

## 4. 实施阶段总览

| 阶段 | 目标 | 建议工期 | 交付结果 |
| --- | --- | ---: | --- |
| 第一阶段 | 修复正确性与工程基线 | 3～5 天 | 实现与设计一致，敏感配置清理，关键竞态测试补齐 |
| 第二阶段 | 建立可复现环境与可靠事件 | 4～6 天 | Compose、Testcontainers、CI、事务 Outbox |
| 第三阶段 | 增加安全、可观测性和量化证据 | 4～6 天 | JWT/RBAC、监控面板、故障注入、压测报告 |

## 5. 第一阶段：正确性与工程基线

### T1. 修复送达超时双通道

**问题**

`DelayQueue.addDeliver` 已定义但没有被调用。当前订单抢单成功后只设置数据库中的 `deliver_deadline_at`，未将送达超时事件写入 Redis ZSet。

**实现方案**

- 在抢单成功并读取最新订单后，调用 `delayQueue.addDeliver(orderId, deliverDeadlineAt)`。
- Redis 写入失败时不得回滚已经成功的数据库抢单事务。
- 数据库扫描任务继续作为完备性兜底。
- 终态订单对应的无效 ZSet 事件由消费者幂等移除。

**主要改动位置**

- `GrabService`
- `DelayQueue`
- `DelayQueuePoller`
- `TimeoutDualSafetyIT`

**验收标准**

- 抢单成功后 `delay:deliver` 中存在对应成员。
- 到期事件触发后订单只被取消和退款一次。
- 删除 Redis 事件后，数据库扫描仍能完成超时退款。
- 同一事件被两个 Worker 同时处理时，只有一个数据库 CAS 成功。

### T2. 严格校验送达截止时间

**问题**

当前送达状态更新只校验订单状态和接单人，没有校验 `deliver_deadline_at`。在截止时间已过、扫描任务尚未执行的窗口内，订单仍可能被送达并结算。

**实现方案**

将送达 SQL 调整为：

```sql
UPDATE t_errand_order
   SET status = 'DELIVERED',
       delivered_at = NOW(3),
       version = version + 1
 WHERE id = ?
   AND status = 'TAKEN'
   AND taker_id = ?
   AND deliver_deadline_at > NOW(3);
```

当更新行数为 0 时，读取订单并区分无权限、状态非法和已过期等错误原因。

**验收标准**

- 截止时间前送达成功。
- 截止时间后送达稳定返回 `409 EXPIRED`。
- `deliver` 与 `timeoutDeliver` 并发执行时只产生一个合法终态。
- 无论哪一方胜出，资金不变量均成立。

### T3. 修正 Redis 前置过滤语义

**问题**

Redis 返回 `FILTERED` 后仍会读取订单，因此只能减少数据库更新，不能减少数据库总请求。

**实现方案**

- Redis marker 同时保存截止时间和发布者 ID。
- Lua 脚本在 Redis 内判断自抢，不消费有效 marker。
- marker 已被消费时直接返回统一抢单冲突，不再查询 MySQL。
- marker 缺失、Redis 超时或 Redis 不可用时降级到 MySQL CAS。
- 对外错误信息不依赖失败后的数据库读取，避免为了精确报错增加热点查询。

**验收标准**

- 已被过滤的请求不执行订单查询和更新 SQL。
- 自抢请求不会消费 marker，其他用户仍可正常抢单。
- Redis 不可用时仍然只有一个赢家。
- 开启过滤后，热点订单的数据库请求数相对关闭过滤显著下降。

### T4. 隔离系统账户

**问题**

MINT 和 PLATFORM 账户目前可能调用普通用户业务。平台用户作为接单人时，结算分录可能落到相同唯一键并持续失败。

**实现方案**

- 普通业务仅允许 `USER` 角色发布和抢单。
- MINT 只允许作为资金发行账户。
- PLATFORM 只允许接收佣金。
- 在 Service 层进行角色校验，数据库层保留现有资金约束。
- 对结算分录先按 `(userId, accountType)` 聚合，再统一写入，增加防御能力。

**验收标准**

- MINT 和 PLATFORM 无法发布或抢单。
- 系统账户异常参与时返回明确业务错误。
- 任意允许的订单结算都不会因账本唯一键冲突永久停留在 `DELIVERED`。

### T5. 加强幂等语义

**问题**

当前重复幂等键直接返回已有状态，但没有验证两次请求的业务参数是否一致。

**实现方案**

- 为 `t_idempotent_op` 增加 `request_hash` 和可选的 `response_json`。
- 对规范化请求参数计算 SHA-256。
- 相同 key、相同参数视为合法重放。
- 相同 key、不同参数返回 `409 IDEMPOTENCY_CONFLICT`。
- 幂等记录、账户变化和账本写入必须在同一事务中提交。

**验收标准**

- 同一充值请求重复执行只入账一次。
- 同 key 同参数返回一致结果。
- 同 key 不同金额或用户返回 409。
- 并发重复请求仍只产生一组账本分录。

### T6. 清理敏感配置并建立 Git 基线

**实现方案**

- 将数据库、Redis 配置改为环境变量，并提供本地默认值或开发 profile。
- 新增 `.env.example`，只记录变量名和非敏感示例。
- 移除 Python 脚本中的硬编码口令。
- 新增 `.gitignore`，忽略 `target/`、IDE 文件、日志和 `.env`。
- 初始化 Git 仓库并按独立功能提交。

建议环境变量：

```text
DB_URL
DB_USERNAME
DB_PASSWORD
REDIS_HOST
REDIS_PORT
REDIS_PASSWORD
JWT_SECRET
```

**验收标准**

- 仓库中搜索不到真实密码。
- 克隆项目后可根据 `.env.example` 完成配置。
- 构建产物和本地配置不会进入 Git。

## 6. 第二阶段：可复现环境与可靠事件

### T7. Docker Compose 一键启动

**实现方案**

新增以下内容：

- 应用 `Dockerfile`，使用多阶段构建或只运行已构建 JAR。
- `compose.yml`，包含 MySQL 8、Redis 和应用。
- MySQL、Redis 健康检查。
- 应用等待依赖健康后启动。
- 数据库使用 Flyway 自动迁移，不再依赖手工建表。

**验收标准**

执行以下命令后能够完整运行：

```bash
docker compose up --build
```

- 健康检查成功。
- 冒烟脚本可完成注册、充值、发单、抢单、送达、结算和对账。
- 删除容器并重新创建后，初始化流程仍然稳定。

### T8. 使用 Testcontainers 隔离集成测试

**实现方案**

- 引入 MySQL 和 Redis Testcontainers。
- 测试启动时动态注入连接信息。
- 不再依赖本机固定端口和固定密码。
- 通过 Toxiproxy 模拟 Redis 断连、延迟和恢复。
- 测试用 Redis 与开发环境完全隔离。

**重点场景**

- Redis 容器停止时抢单正确性不变。
- Redis 请求超过客户端超时时能快速降级。
- Redis 恢复后 marker 能从数据库重建。
- MySQL 真实 InnoDB 下并发抢单只有一个赢家。

**验收标准**

- 任意安装 Docker 的开发机可直接运行 `mvn verify`。
- 测试不读写本机数据库和默认 Redis DB。
- CI 与本地使用同一套集成测试。

### T9. 建立 CI

**实现方案**

新增 GitHub Actions 工作流：

1. 检出代码并安装 Java 21。
2. 缓存 Maven 依赖。
3. 运行格式或静态检查。
4. 运行 `mvn verify`。
5. 生成测试及覆盖率报告。
6. 构建 Docker 镜像，验证镜像能够启动。

可选质量门禁：

- JaCoCo 行覆盖率不低于 70%。
- 核心 service 分支覆盖率不低于 80%。
- Flyway migration 校验必须通过。

**验收标准**

- 每个提交和 Pull Request 自动执行测试。
- 失败测试会阻止合并。
- README 展示构建状态，但不把覆盖率数字当作唯一质量证明。

### T10. 引入事务 Outbox

**目标**

解决“数据库事务提交成功，但进程在写 Redis 之前退出”造成的数据库与异步索引双写不一致。

**建议表结构**

```sql
CREATE TABLE t_outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(64) NOT NULL,
    biz_id BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_until DATETIME(3) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event (event_type, biz_id),
    KEY idx_outbox_poll (status, next_retry_at, id)
) ENGINE=InnoDB;
```

**事件类型**

- `ORDER_PUBLISHED`：创建抢单 marker 与领取截止事件。
- `ORDER_TAKEN`：创建送达截止事件。
- `ORDER_TERMINAL`：清理无效 marker 和延迟事件。
- 可选 `ORDER_DELIVERED`：触发异步结算。

**Worker 方案**

- 使用 `SELECT ... FOR UPDATE SKIP LOCKED` 分批领取任务。
- 多实例可并行消费不同事件。
- Redis `SET`、`ZADD` 等消费者操作必须天然幂等。
- 失败使用指数退避更新 `next_retry_at`。
- 超过最大重试次数进入 `DEAD` 状态。
- 单条失败事件不能阻塞后续事件。

**验收标准**

- 业务数据和 Outbox 事件在同一事务中提交。
- 重复消费不会产生重复业务效果。
- 应用在事务提交后、Redis 写入前退出，重启后仍能恢复事件。
- 两个应用实例不会长期重复处理同一事件。
- DEAD 事件可查询、告警并支持人工重放。

### T11. 改造后台任务

**实现方案**

- 移除无日志的 `catch (RuntimeException ignored)`。
- 日志至少包含任务类型、订单 ID、重试次数和异常分类。
- marker 重建使用 ID 游标分页，避免永远只处理最前面的 500 条。
- 结算重试增加指数退避和最大重试次数。
- 定时扫描限制单批处理时间，避免任务重叠堆积。
- 增加任务执行耗时、成功数、失败数和积压量指标。

**验收标准**

- 任意后台失败均有可检索日志或状态记录。
- 大于一个批次的数据能够全部处理。
- 毒事件不会造成任务头部阻塞。

## 7. 第三阶段：安全、可观测性与量化证据

### T12. Spring Security、JWT 与 RBAC

**实现方案**

- 为用户增加唯一登录标识和 BCrypt 密码摘要。
- 提供注册和登录接口。
- 使用短期 JWT access token。
- 从 `SecurityContext` 获取当前用户，不再接受请求体中的 `publisherId` 或 `userId`。
- `/api/admin/**` 仅允许 ADMIN。
- 普通用户只能查询自己的钱包。
- 充值改为管理员测试操作，或实现带签名及幂等键的模拟支付回调。

**验收标准**

- 未登录请求无法执行资金和订单操作。
- 用户不能冒用他人身份发布、取消、送达或查看钱包。
- 普通用户不能访问对账及指标管理接口。
- 安全边界具有 Controller 集成测试。

### T13. 抢单限流

**实现方案**

- 使用 Redis Lua 实现用户维度和接口维度的滑动窗口或令牌桶。
- 热点抢单使用较严格的短周期阈值。
- 返回标准 `429 Too Many Requests`。
- Redis 不可用时根据项目定位选择 fail-open，并记录降级指标。

**验收标准**

- 超过阈值的请求不会访问数据库。
- 不同用户之间互不影响。
- Redis 故障时业务正确性不受影响。

### T14. Actuator、Micrometer、Prometheus 与 Grafana

**建议指标**

| 指标 | 类型 | 含义 |
| --- | --- | --- |
| `grab_attempt_total` | Counter | 抢单请求总数 |
| `grab_redis_filtered_total` | Counter | Redis 拦截请求数 |
| `grab_db_cas_total` | Counter | 实际进入 MySQL CAS 的请求数 |
| `grab_winner_total` | Counter | 抢单成功数 |
| `settlement_retry_total` | Counter | 结算重试次数 |
| `outbox_pending` | Gauge | 待消费事件数量 |
| `outbox_dead` | Gauge | 死信事件数量 |
| `timeout_queue_lag_seconds` | Gauge/Timer | 超时事件处理延迟 |
| `recon_failure_total` | Counter | 对账失败次数 |
| `recon_last_success_timestamp` | Gauge | 最近一次对账成功时间 |

**健康检查原则**

- MySQL 不可用时 readiness 失败。
- Redis 不进入强制 readiness，因为系统允许 Redis 故障降级。
- Redis 状态可通过独立健康项和指标展示。

**验收标准**

- Grafana 能显示抢单链路、异步任务和资金对账三个面板。
- 能从指标判断 Redis 过滤是否有效。
- 对账失败和 Outbox 死信可触发告警。

### T15. 建立严谨压测体系

**工具选择**

保留现有 Python 脚本做开发冒烟，使用 k6 或 Gatling 运行正式场景。

**压测场景**

1. 单个热门订单，500～1000 个并发抢单请求。
2. 1000 个订单，每单 10～50 个竞争者。
3. 持续 5～15 分钟混合执行发布、抢单、取消、送达和结算。
4. 压测过程中停止 Redis，保持一段时间后恢复。
5. 压测结束后自动执行全部资金不变量对账。

**测试方法**

- 固定 CPU、内存、JVM 参数、连接池大小及依赖版本。
- 每轮先预热，再采集正式数据。
- 每个场景至少运行 5 次，记录中位数及波动范围。
- 同时测试 Redis 过滤开启与关闭。
- 使用应用指标和 MySQL Performance Schema 统计数据库负载。
- 保存原始结果，图表只能由原始数据生成。

**报告至少包含**

- 吞吐量 RPS。
- P50、P95、P99 和最大延迟。
- 错误率及错误类型分布。
- Redis 过滤率。
- MySQL CAS 请求数及冲突数。
- 数据库连接池占用。
- Redis 故障期间与恢复后的指标变化。
- 最终赢家数量和 5 项资金不变量结果。

**验收标准**

- 所有场景都可以通过脚本复现。
- Redis 开关对数据库压力的影响有明确数字。
- Redis 故障期间不出现双赢家或资金错误。
- README 中只引用真实测量数据。

### T16. 故障注入与恢复演示

**故障场景**

- Redis 断连、延迟和数据清空。
- 应用在业务事务提交后、Outbox 消费前退出。
- Outbox 消费者重复投递同一事件。
- 两个结算 Worker 同时处理同一订单。
- 延迟队列事件丢失。
- 人为修改账户余额制造账实漂移。

**验收标准**

- 每个场景都有自动化测试或可重复脚本。
- 故障恢复后订单最终进入合法终态。
- 对账能识别无法自动修复的资金异常。
- 项目演示可以在 5～10 分钟内完整呈现核心可靠性设计。

## 8. 核心测试矩阵

| 竞争或故障场景 | 预期结果 | 必验不变量 |
| --- | --- | --- |
| 多用户并发抢同一订单 | 恰好一个赢家 | 订单唯一接单人 |
| 自抢与正常抢单并发 | 自抢失败，marker 不丢失 | 正常用户仍可成功 |
| 抢单、取消、领取超时三方竞争 | 只产生一个合法状态结果 | 托管金额与开放订单一致 |
| 送达与送达超时竞争 | SETTLED 或 CANCELLED 二选一 | 只结算或退款一次 |
| 20 路并发结算 | 只有一组账户变化 | SETTLE 分录唯一、总和为 0 |
| 相同幂等键不同参数 | 返回 409 | 不新增余额或账本 |
| Redis 完全断开 | 降级到 MySQL | 仍只有一个赢家 |
| Redis 数据清空后恢复 | marker 自动重建 | 数据库状态不变 |
| Outbox 重复消费 | 消费幂等 | 无重复事件效果 |
| 应用中途退出后重启 | 未完成事件继续处理 | 最终一致 |
| 人为修改账户余额 | 对账失败并定位异常 | INV-2 失败 |

建议集成测试不少于 25 个，重点覆盖竞态和故障边界，而不是单纯追求代码覆盖率。

## 9. 推荐提交顺序

每个提交应保持可构建、可测试，建议顺序如下：

1. `chore: externalize local database and redis credentials`
2. `fix: enforce delivery deadline in order state transition`
3. `fix: enqueue delivery timeout after successful grab`
4. `fix: isolate system accounts from user order flows`
5. `test: cover grab cancel timeout and settlement races`
6. `test: migrate integration dependencies to testcontainers`
7. `build: add docker compose and continuous integration`
8. `feat: persist reliable domain events with transactional outbox`
9. `feat: add outbox retry dead letter and replay support`
10. `feat: authenticate users and enforce role based access`
11. `feat: expose business metrics and operational health checks`
12. `perf: add reproducible load and failure test scenarios`
13. `docs: publish benchmark report and failure recovery guide`

## 10. 项目完成定义

满足以下条件后，可以将本项目作为简历主项目：

- `docker compose up --build` 可在新环境中一键启动。
- `mvn verify` 可在 CI 中稳定通过。
- 使用真实 MySQL、Redis 的集成测试不少于 25 个。
- Redis 宕机、重复事件、并发结算和应用重启不会破坏单赢家及资金守恒。
- 所有用户身份来自认证上下文，管理接口具有 RBAC 保护。
- Prometheus/Grafana 能展示核心业务和可靠性指标。
- 存在固定环境、可复现脚本和原始数据支撑的压测报告。
- README、设计文档和代码实现保持一致。
- 能在面试中现场演示一次 Redis 故障降级和恢复过程。

## 11. 简历表述模板

第一条突出正确性设计：

> 基于 Java 21、Spring Boot、MySQL 和 Redis 实现校园跑腿抢单系统，以 InnoDB 条件更新保证高并发场景下抢单恰一成功，Redis Lua 提供可降级流量过滤；设计托管账户、复式账本及三层幂等结算机制，并通过对账不变量验证资金一致性。

第二条突出可靠事件与故障恢复：

> 基于事务 Outbox、幂等消费者和数据库兜底扫描解决订单状态与 Redis 延迟索引的双写一致性问题，支持 Redis 断连、事件重复投递和应用重启后的自动恢复。

第三条必须使用真实压测数据：

> 在 `[并发数]` 并发、`[订单数]` 订单场景下达到 `[RPS]`，P99 为 `[延迟] ms`；Redis 前置过滤使数据库请求量下降 `[比例]%`，故障注入期间仍保持单赢家和资金守恒。

不得在完成可复现压测前填写或估算性能数字。

## 12. 建议立即开始的任务

第一批应只处理 P0 正确性问题：

- [ ] 外部化数据库和 Redis 配置，移除硬编码密码。
- [ ] 为送达状态更新增加截止时间守卫。
- [ ] 抢单成功后补写送达超时事件。
- [ ] 增加 `deliver` 与 `timeoutDeliver` 并发测试。
- [ ] 禁止 MINT、PLATFORM 参与普通订单流程。
- [ ] 增加系统账户相关结算回归测试。
- [ ] 修正 Redis 前置过滤的实现和文档描述。
- [ ] 初始化 Git 并加入 `.gitignore`。

完成这一批后，再开始 Testcontainers 与事务 Outbox，避免在已知正确性缺口上继续叠加基础设施。
