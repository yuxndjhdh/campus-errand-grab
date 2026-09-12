# Campus Errand Grab：高并发抢单与资金一致性平台

## 项目简介

基于 Java 21、Spring Boot、MySQL 和 Redis 构建校园跑腿抢单单体服务，重点解决热点订单并发竞争、订单状态与 Redis 派生状态双写、重复结算和资金一致性问题。系统以 MySQL 作为订单与资金唯一事实来源，Redis 负责前置过滤、限流和延迟索引，并通过事务 Outbox、故障降级、自动对账和可复现压测形成从代码到运行证据的完整闭环。

## 项目亮点

1. **MySQL CAS + Redis Lua 单赢家抢单**：使用 Redis Lua 对热点订单进行前置过滤，以 InnoDB 条件更新作为最终裁决；Redis 不可用时自动回退数据库 CAS，避免缓存故障导致双赢家。

2. **复式账本与事务化结算**：设计 AVAILABLE/FROZEN 托管账户、MINT/PLATFORM 系统账户和 FREEZE/SETTLE/CANCEL 分录；结算同时执行幂等键、账户更新、订单状态 CAS 和账本写入，保证重复请求不会重复扣款或入账。

3. **Outbox 可靠事件与崩溃恢复**：使用 `FOR UPDATE SKIP LOCKED`、处理租约、指数退避、`DEAD` 状态和人工重放，拆分事件领取、Redis 副作用和状态更新事务；支持重复投递、租约过期重领和单条毒事件隔离。

4. **故障降级与可验证恢复**：通过 Testcontainers、Toxiproxy 和 Compose 故障脚本验证 Redis 断连、Outbox 崩溃、重复结算和账实漂移；故障期间保持单赢家、无 HTTP 5xx，恢复后自动重建 Redis 派生状态并通过五项资金对账不变量。

5. **安全边界与接口级 RBAC**：实现 BCrypt 密码摘要、HMAC-SHA256 JWT、USER/ADMIN/PLATFORM/MINT 角色隔离和认证上下文取身份；通过 Web 集成测试覆盖未登录、无效令牌、身份冒用、钱包越权和管理接口越权。

6. **可复现性能与监控证据**：接入 Micrometer、Prometheus、Grafana、Docker Compose 和 GitHub Actions；完成 10 个热点/多订单压测变体，每个变体 1 次预热和 5 次正式运行，并补充持续负载、Redis 故障和结算重试原始报告。

## 可量化工作成果

以下数字均来自仓库中的本地 Docker 验证环境，不代表生产容量上限：

| 维度 | 量化结果 | 证据 |
| --- | --- | --- |
| 测试稳定性 | 67 个测试，0 失败、0 错误、0 跳过；空 Maven 仓库构建和完整测试连续三次通过 | `target/surefire-reports/`、`reports/runtime/maven-verify-current-run-*.log` |
| 代码覆盖 | 指令覆盖率 79%，分支覆盖率 56% | `target/site/jacoco/index.html` |
| 热点订单 500 并发 | Redis 前置过滤开启时中位 RPS 380.03，相比关闭时 351.95 高约 8.0%；中位 P99 从 1142.41 ms 降至 1056.69 ms | `reports/benchmarks/benchmark-report.md` |
| 热点订单数据库压力 | 500 并发 5 次正式运行中，DB CAS 从 2500 次降至 5 次，减少约 99.8%；过滤率为 99.80% | `reports/benchmarks/benchmark-report.md`、`reports/benchmarks/raw/` |
| 热点订单 1000 并发 | Redis 前置过滤将 DB CAS 从 5000 次降至 5 次，减少约 99.9%；该配置下中位 RPS 为 333.84，关闭时为 361.69，性能差异需结合数据库压力解读 | `reports/benchmarks/benchmark-report.md` |
| 持续负载 | 300 秒完成 95,220 次抢单请求、4,761 个订单和 4,761 次结算，317.35 RPS，HTTP 5xx 为 0 | `reports/benchmarks/raw/sustained-300s-on-pool20-20260911-174225.json` |
| Redis 故障 | 200 个客户端竞争同一订单仍只有 1 个赢家，HTTP 5xx 为 0；故障期间 Redis 降级指标从 0 增至 239、DB CAS 增加 200 次，恢复耗时约 7.4 秒且对账通过 | `reports/benchmarks/raw/redis-fault-200-on-pool20-20260911-174813.json` |
| 结算幂等 | 20 个并发的送达请求只有 1 次成功，1 条结算幂等记录、3 条结算分录，账本总额为 0 | `reports/failure-tests/index.md`、最新成功报告 |
| 远程 CI | 历史 GitHub Actions Run `34559235088` 成功完成 Maven 验证、Compose 校验、Docker 构建和报告上传；当前未提交工作区未再次执行远程 CI | GitHub Actions Run `34559235088` |
| 本轮工作区验收 | 本地最终验收 `PASS`；远程 CI 未检查 | `reports/runtime/current-workspace-validation.json` |
| 支付/退款沙箱 | 支付回调和退款各完成 1,000 次重复回放，通知/补偿/对账断言通过；仅限本地 SANDBOX | `reports/payment/payment-validation-report.md` |
| 同机恢复演练 | Redis 与应用各 3 轮重启恢复，备份校验通过；不覆盖 HA、跨主机或 PITR | `reports/disaster-recovery/disaster-recovery-report.md` |

## 可直接放入简历的版本

**Campus Errand Grab｜高并发抢单与资金一致性平台**  
技术栈：Java 21、Spring Boot、MySQL、Redis、Flyway、Spring Security、Testcontainers、Toxiproxy、Prometheus、Grafana、Docker Compose

- 基于 MySQL 条件更新和 Redis Lua 前置过滤实现热点订单抢单，Redis 故障时回退 DB CAS；500 并发场景下 Redis 过滤使中位 RPS 从 351.95 提升至 380.03（+8.0%），中位 P99 从 1142.41 ms 降至 1056.69 ms，DB CAS 请求从 2500 次降至 5 次。证据：`reports/benchmarks/benchmark-report.md`。
- 设计 AVAILABLE/FROZEN 托管账户、MINT/PLATFORM 系统账户和复式账本，将幂等键、账户变更、订单状态 CAS 与结算分录纳入同一事务；20 路并发重复结算仅 1 次成功，账本总额保持为 0。
- 实现事务 Outbox 的 `SKIP LOCKED` 领取、租约、指数退避、`DEAD` 和人工重放，拆分 Redis 副作用与数据库状态更新；覆盖重复投递、租约过期、崩溃恢复和毒事件隔离。
- 使用 Testcontainers、Toxiproxy 和 Compose 故障脚本验证 Redis 断连、Outbox 崩溃、重复结算和账实漂移；Redis 故障期间 200 客户端竞争仍保持单赢家、HTTP 5xx 为 0，恢复约 7.4 秒后五项资金对账不变量全部通过。
- 基于 BCrypt、HMAC-SHA256 JWT 和 RBAC 保护订单、钱包及管理接口，通过 Web 集成测试覆盖未登录、无效令牌、身份冒用和跨用户钱包访问等场景。
- 建立 Prometheus/Grafana 监控和可复现压测链路；300 秒持续负载完成 95,220 次抢单请求、4,761 次结算，317.35 RPS，HTTP 5xx 为 0，并将原始 JSON、图表和报告保存到仓库证据目录。

## 使用边界

### 可以强调的内容

- “在本地 Docker 验证环境中”完成的测试结果。
- 单赢家、资金守恒、故障降级和重复结算等正确性结果。
- Redis 前置过滤对热点订单数据库压力和延迟的改善。
- 原始 JSON、自动化脚本、Grafana 快照和 CI 记录形成的可追溯证据链。

### 不应直接声称的内容

- “系统生产容量为 400+ RPS”或“支持 1000 并发生产流量”。
- “Redis 故障下保证 100% 可用”。
- “避免了线上资金损失”或“已服务真实用户”。
- “已达到生产就绪”，因为生产环境仍需补齐数据库/Redis 高可用、备份恢复、密钥管理、TLS、支付回调验签、登录风控和集中式运维。

## 面试时的核心讲法

这个项目的核心不是把 Redis、MySQL、JWT 和监控拼在一起，而是明确划分事实来源和加速层：MySQL 决定订单状态与资金，Redis 只减少无效竞争和提供及时性；Outbox 解决数据库状态与 Redis 派生状态的最终一致性；复式账本和对账不变量验证资金守恒；故障脚本和压测原始数据则证明这些设计在并发和异常路径下确实成立。
