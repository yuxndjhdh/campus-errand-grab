# Campus Errand Grab 剩余验收与压测任务

## 1. 文档目的

本文记录本地验收任务的执行结果，并保留尚未授权执行的 Git/远程 CI 工作。当前本地可执行项已经完成；生产安全、高可用和业务外围能力另见 `docs/outstanding-work-plan.md`。

本轮暂不处理以下 Git 相关工作：

- Git 提交拆分。
- 推送工作区改动。
- 分支整理。

远程 GitHub Actions 的最终验收依赖后续推送，因此本阶段只处理可以在本地完成的 CI 可复现性修复；远程工作流全绿记录留到恢复 Git 提交与推送后完成。

## 2. 当前已完成的基础

以下内容已有代码、测试报告或运行结果，不再作为主要开发任务：

- MySQL CAS 抢单、订单状态机和资金事务。
- 复式账本、资金对账、结算幂等和请求指纹。
- 送达截止时间守卫、送达与超时竞态测试。
- Redis Lua 前置过滤、限流、故障降级和恢复重建。
- Outbox 独立领取事务、租约、重试、DEAD 和人工重放。
- JWT、BCrypt、RBAC 和身份越权测试。
- 本地空 Maven 仓库构建及完整测试连续三次通过：每次 53 个测试，0 失败、0 错误、0 跳过。
- JaCoCo 报告生成。
- App、MySQL、Redis、Prometheus、Grafana 的 Compose 环境启动。
- Redis 故障、Outbox 恢复、重复结算、对账漂移四类故障脚本成功运行。
- 正式 Python HTTP 压测矩阵、300 秒持续负载、Redis 故障和结算重试均已生成原始报告。

## 3. 剩余任务总览

| 优先级 | 任务 | 当前缺口 | 完成标志 |
| --- | --- | --- | --- |
| P0 | 完整测试连续运行三次 | 已完成 | 三次均为 `BUILD SUCCESS`，每次 53 个测试且无失败、错误和跳过 |
| P0 | 修复干净环境的 Maven 可复现性 | 已完成 | 临时空 Maven 仓库构建成功，依赖由项目设置解析 |
| P1 | 补齐 Compose 运行验收证据 | 已完成 | 运行状态、冒烟结果、Flyway 迁移记录已落盘 |
| P1 | 完成 Grafana 和告警实测 | 已完成 | 三类面板快照和告警触发/恢复证据齐全 |
| P1 | 整理故障演示证据 | 已完成 | 成功报告索引清晰，历史失败样本明确排除 |
| P2 | 执行正式压测矩阵 | 已完成 | 10 个变体各 1 次预热和 5 次正式运行，并补充特殊场景 |
| P2 | 生成正式压测结论和图表 | 已完成 | 报告状态为 `COMPLETE`，结论可追溯至原始 JSON |
| P2 | 同步项目文档和简历材料 | 本轮完成 | README、路线图、报告和简历数字已按最新证据同步 |
| P1 | Git 提交、推送和远程 CI | 本轮不执行 | 需要用户授权后再提交、推送并验证远程工作流 |

## 4. P0：测试稳定性与干净环境构建

### 4.1 完整测试连续运行三次

执行：

```powershell
1..3 | ForEach-Object {
    Write-Host "=== verify run $_ ==="
    mvn -s mvn-settings.xml clean verify
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
```

每次运行后检查：

- Surefire 中 `Failures`、`Errors`、`Skipped` 均为 0。
- `TimeoutDualSafetyIT` 的 100 次竞态没有偶发失败。
- `RedisToxiproxyIT` 和 `GrabRedisOutageIT` 没有超时抖动。
- Testcontainers 全部正常退出，没有残留测试容器。
- `target/site/jacoco/index.html` 正常生成。

验收清单：

- [x] 第一次完整测试通过。
- [x] 第二次完整测试通过。
- [x] 第三次完整测试通过。
- [x] 三次测试均无失败、错误或跳过。
- [x] 保存三次运行的时间和汇总结果。

### 4.2 验证 Maven 在干净环境可构建

历史远程 GitHub Actions 曾在 Maven 编译阶段出现依赖解析失败。本轮已用临时空 Maven 仓库完成构建，证明本地不依赖现有用户缓存；当前未提交工作区仍未重新推送，因此不能把该本地证据表述为当前远程 CI 结果。

需要检查：

- `mvn-settings.xml` 的镜像配置是否能在 Linux Runner 中下载全部依赖。
- 配置中是否存在 Windows 绝对路径。
- Maven 镜像是否错误拦截插件仓库或 Central。
- `pom.xml` 中全部直接依赖和插件版本是否可从配置的仓库获取。
- 使用空 Maven 本地仓库执行构建，验证不依赖现有缓存。

建议命令：

```powershell
$cleanRepo = Join-Path $env:TEMP "campus-errand-m2-clean"
mvn -s mvn-settings.xml -Dmaven.repo.local=$cleanRepo clean verify
```

验收清单：

- [x] 空 Maven 本地仓库能够解析全部依赖。
- [x] 源码和测试均编译成功。
- [x] Testcontainers 测试在干净依赖环境中通过。
- [x] Compose 校验和 Docker 镜像构建在本地通过。
- [x] 不依赖 `C:/Users/YU/.m2/repository` 等机器专属路径。

> 远程 GitHub Actions 全绿记录暂不在本轮处理；恢复推送后再完成最终验收。

## 5. P1：Compose 运行证据

### 5.1 一键启动和冒烟测试

重新使用可丢弃数据环境完成一次标准验收：

```powershell
docker compose config
docker compose build
docker compose up -d
docker compose ps
```

随后执行项目冒烟脚本，覆盖：

1. 用户注册和登录。
2. 充值。
3. 发布订单。
4. 抢单。
5. 确认送达。
6. 结算。
7. 最终资金对账。

还需确认：

- 空数据库上 Flyway 自动完成全部迁移。
- MySQL 和 Redis 健康后应用再启动。
- 应用 readiness 返回成功。
- Prometheus 能抓取应用指标。
- Grafana 能自动加载 datasource 和 dashboard。
- 容器重建后持久化策略符合设计。
- Redis 停止时应用仍按“可降级”策略工作。

应生成：

```text
reports/runtime/compose-services.txt
reports/runtime/smoke-result.json
reports/runtime/flyway-migrations.txt
```

验收清单：

- [x] `docker compose up --build` 无需手工建库或建表。
- [x] 冒烟脚本退出码为 0。
- [x] 对账的 5 项不变量全部通过。
- [x] 三份运行证据文件生成且不包含密码、JWT 等敏感信息。

## 6. P1：Prometheus、Grafana 和告警验收

当前 Prometheus 规则接口返回成功，Grafana 健康检查正常，但这只能证明服务已启动，不能证明面板和告警可用。

### 6.1 指标验收

制造包含正常和故障路径的业务流量，确认以下指标存在且数值按预期变化：

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

重点检查：

- Redis 故障期间降级计数增加。
- Redis 故障期间 DB CAS 调用增加。
- Redis 恢复后指标和业务处理恢复正常。
- 指标标签不包含 `userId`、`orderId` 等高基数字段。

### 6.2 面板验收

检查面板的查询语句、图例、单位、时间窗口和阈值，并保存脱敏截图：

```text
reports/monitoring/grab-pipeline.png
reports/monitoring/outbox-and-jobs.png
reports/monitoring/financial-reconciliation.png
```

### 6.3 告警验收

至少实际触发并恢复以下告警：

- Outbox DEAD 事件大于 0。
- 资金对账失败。
- 超时队列延迟超过阈值。
- Redis 降级状态持续超过阈值（如果规则中已配置）。

验收清单：

- [x] 三类 Grafana 面板内容正确。
- [x] Redis 故障期间指标能够反映降级过程。
- [x] DEAD、对账失败和队列延迟告警均实际触发。
- [x] 故障恢复后告警自动恢复。
- [x] 查询结果和面板截图保存到仓库规定目录。

## 7. P1：故障演示报告整理

四类脚本已有成功报告：

- Redis 故障：恰好一个赢家，降级指标增加，最终对账通过。
- Outbox 崩溃恢复：事件最终进入 `PUBLISHED`，最终对账通过。
- 重复结算：并发请求只形成一次有效结算，最终对账通过。
- 对账漂移：能够检测漂移、恢复数据并重新通过对账。

仍需整理：

- `reports/failure-tests/` 中有一份重复结算的历史失败报告，其失败原因是测试数据复用了不同参数的幂等键。
- 为每类场景明确指定一份最终成功报告。
- 增加报告索引，记录命令、开始时间、恢复时间、核心断言和结果文件。
- 确认任一断言失败时脚本返回非零退出码。
- 确认完整演示能在 5～10 分钟内完成。

验收清单：

- [x] 四类场景都能以单条命令运行。
- [x] 四类最终成功报告都有明确索引。
- [x] 历史失败样本被删除、归档或明确标注为失败样本。
- [x] 所有成功报告都包含最终对账结果。
- [x] 演示步骤和预期结果与 `docs/failure-recovery.md` 一致。

## 8. P2：正式压测矩阵

正式报告 `reports/benchmarks/benchmark-report.md` 当前状态为 `COMPLETE`。矩阵包含 10 个热点/多订单变体，每个变体 1 次预热和 5 次正式运行，并补充 300 秒持续负载、Redis 故障和结算重试场景；这些数据仍只描述本地 Windows Docker 环境，不代表生产容量上限。

### 8.1 测试前准备

- 固定 CPU、内存、JDK、JVM、MySQL、Redis 和 Hikari 配置。
- 使用独立、可清理的压测数据库。
- 记录限流配置及测试身份数量。
- 每个场景先预热，再运行至少 5 次正式测试。
- 每轮结束执行资金对账。

### 8.2 必测场景

| 场景 | 主要参数 | 对照项 |
| --- | --- | --- |
| 单热点订单 | 500、1000 并发 | Redis 前置过滤开/关 |
| 多订单竞争 | 1000 个订单，每单 10、20、50 人 | Redis 前置过滤开/关 |
| 混合持续负载 | 连续运行 5～15 分钟 | 不同 Hikari 连接池大小 |
| Redis 故障 | 压测过程中停止并恢复 Redis | 故障前、故障中、恢复后 |
| 结算重试 | 重复和并发投递 | 单次正常结算 |

### 8.3 每轮采集内容

- RPS。
- P50、P95、P99 和最大延迟。
- HTTP 系统错误率。
- 409、429 等业务拒绝分布。
- Redis 过滤量和限流量。
- MySQL CAS 请求数和冲突数。
- Hikari 活跃、空闲、等待连接数。
- Outbox backlog、失败次数和消费延迟。
- Redis 故障持续时间及恢复时间。
- 每个热点订单的赢家数量。
- 最终 5 项资金不变量。

验收清单：

- [x] 每个场景至少完成 1 次预热和 5 次正式运行。
- [x] Redis 开关对照使用完全相同的业务负载。
- [x] 所有原始结果写入 `reports/benchmarks/raw/`。
- [x] 所有场景均无双赢家和资金异常。
- [x] 系统错误与业务拒绝分开统计。

## 9. P2：压测报告和图表

正式报告已经由原始数据生成，当前状态为 `COMPLETE`。

报告必须包括：

1. 测试目标和假设。
2. 硬件、软件及应用配置。
3. 场景、参数、预热方式和运行次数。
4. 原始数据索引。
5. RPS 和延迟分布。
6. Redis 过滤开关对数据库压力的影响。
7. Redis 故障期间的降级和恢复表现。
8. Outbox、连接池和资金对账结果。
9. 异常值、瓶颈及结论适用范围。

需要生成：

```text
reports/benchmarks/environment.md
reports/benchmarks/raw/*.json
reports/benchmarks/charts/*
reports/benchmarks/benchmark-report.md
```

验收清单：

- [x] 图表由原始数据生成，不手工填写数据点。
- [x] 每个表格和结论都能定位到原始结果文件。
- [x] 报告不使用估算数字。
- [x] 矩阵、持续负载和故障场景结果均明确标注适用范围。
- [x] `benchmark-report.md` 状态为 `COMPLETE`。

## 10. P2：文档和简历材料收尾

本轮已完成同步：

- `README.md`
- `docs/implementation-roadmap.md`
- `docs/project-validation-and-ci-follow-up.md`
- `docs/reliability-validation-and-benchmark-execution-plan.md`
- `docs/failure-recovery.md`

同步内容：

- 将已有代码和报告支持的任务标记为完成。
- 删除“当前机器没有 Docker”等过时描述。
- README 补充一键运行、冒烟测试、监控入口和正式压测摘要。
- 保证可靠性结论都能对应到自动化测试或故障报告。
- 只将正式压测报告中可复现的数字写入简历。
- 不把本地压测结果表述为系统整体容量。

验收清单：

- [x] 路线图中每个完成项都有证据路径。
- [x] README、代码、测试和运行方式一致。
- [x] 简历中的每个数字都能追溯到正式报告和原始数据。
- [x] 能在面试中于 5～10 分钟内完成一次 Redis 故障降级演示。

## 11. 推荐执行顺序

```text
空缓存 Maven 构建
  -> 完整测试连续三次
  -> Compose/Flyway/冒烟证据
  -> Prometheus/Grafana/告警实测
  -> 故障报告整理
  -> 正式压测矩阵
  -> 图表与正式报告
  -> README、路线图和简历材料同步
```

## 12. 最终完成定义

在暂不考虑 Git 提交和推送的前提下，同时满足以下条件即可认为本地工程验收完成：

- [x] 干净 Maven 依赖环境能够构建。
- [x] 完整 Testcontainers 测试连续三次通过。
- [x] Compose 一键启动、Flyway 和冒烟流程有结果文件。
- [x] 四类故障演示有可复现的成功报告。
- [x] Prometheus 指标、Grafana 面板和告警均经过实际验证。
- [x] 正式压测覆盖热点、多订单、持续负载和 Redis 故障。
- [x] 仓库包含原始压测数据、图表和正式报告。
- [x] README、路线图、测试、实现和简历材料一致。

## 13. 本轮执行记录

本地验收已完成。关键证据如下：

- 空 Maven 缓存构建和完整测试连续三次通过，每次 53 个测试，失败、错误、跳过均为 0；运行日志见 `reports/runtime/maven-verify-current-run-1.log` 至 `maven-verify-current-run-3.log`。
- 正式矩阵索引为 `reports/benchmarks/raw/run-index-20260911-172249-692653.json`；10 个变体均有 1 次预热和 5 次正式运行。
- 正式报告为 `reports/benchmarks/benchmark-report.md`，状态为 `COMPLETE`；另有 300 秒持续负载、Redis 故障和结算重试原始报告。
- 最新持续负载报告为 `reports/benchmarks/raw/sustained-300s-on-pool20-20260911-174225.json`；Redis 故障和结算重试报告分别为 `redis-fault-200-on-pool20-20260911-174813.json`、`settlement-retry-pool20-20260911-174921.json`。
- 四类故障成功报告索引为 `reports/failure-tests/index.md`；历史失败样本明确排除。
- 监控实测证据为 `reports/monitoring/monitoring-validation.json` 和三张脱敏 PNG 指标快照。
- Git 提交、推送和远程 GitHub Actions 全绿记录按本文第 1 节约束暂不处理。
