# Campus Errand Grab 未完成优化任务执行计划

## 1. 计划目的

本文只安排 `docs/quantified-impact-optimization-plan.md` 中尚未完成或仅部分完成的任务。已完成的文档口径治理、正式压测矩阵、持续负载、Redis 故障降级、结算重试和基础监控验收不重复实施。

执行目标不是增加功能数量，而是让每一项工作形成以下闭环：

```text
现状基线 -> 问题假设 -> 单变量改动 -> 自动化验证 -> 原始证据 -> 可量化结论
```

约束：

- 当前所有性能数字只代表本地 Windows Docker Desktop 受控实验，不表述为生产 SLA。
- “目标值”和“已取得结果”必须分开；完成复测前不得把目标写进简历。
- 性能验收必须同时检查 HTTP 5xx、单赢家、数据库 CAS 和五项资金对账，不只比较 RPS。
- 失败样本必须保留，禁止只选最好的一次运行。
- 本计划不要求创建新分支；是否提交或推送由后续交付安排决定。

截至 2026-09-12，本轮已完成 W0 的本地自动化验收，完成 W1 的 Redis 诊断 A/B，
并形成 W2 安全、W3 监控告警、W4 同机灾备和 W5 本地支付沙箱的部分验收证据。
W6 没有独立压测端和固定预生产资源，保持 `NOT_RUN`。下表和各工作包中的复选框
只反映当前证据覆盖范围，不把未运行的外部系统或生产能力写成完成。

## 2. 当前状态与剩余范围

| 编号 | 任务 | 当前状态 | 剩余工作的核心结果 |
| --- | --- | --- | --- |
| W0 | 当前观测改动收口 | 已完成（本地验收） | 当前工作区的自动化检查、报告生成和文档证据通过；完整人工 diff 审查仍留待 Git 交付审查 |
| W1 | Redis 路径性能优化 | 部分完成 | 已完成受控诊断和 trade-off 结论；尚未证明有稳定吞吐优化 |
| W2 | 生产安全闭环 | 部分完成 | 本地配置、权限和登录风控通过；Trivy 依赖/镜像扫描未在本机运行 |
| W3 | 可观测性、SLO 与告警闭环 | 部分完成 | 本地指标、告警、Runbook 和时间线通过；OTel 与外部告警路由未运行 |
| W4 | 高可用、备份与恢复 | 部分完成 | 同机备份、Redis/应用恢复和状态重建通过；HA、跨主机和 PITR 未覆盖 |
| W5 | 支付、退款与通知闭环 | 部分完成 | 本地 SANDBOX 回调、退款、通知和补偿回放通过；未接真实渠道和完整故障矩阵 |
| W6 | 预生产容量基线 | 未运行 | 尚无独立压测端、固定预生产资源和 30～60 分钟稳定性证据 |

当前可复用证据：

- 正式压测：`reports/benchmarks/benchmark-report.md`
- Redis 运行时诊断：`reports/benchmarks/runtime-bottleneck-report.md`
- 监控告警验证：`reports/monitoring/monitoring-validation.json`
- 文档一致性：`reports/document-consistency.json`
- 故障演练索引：`reports/failure-tests/index.md`

## 3. 执行顺序

```text
W0 当前改动收口
  -> W1 Redis 性能优化
  -> W2 生产安全闭环
  -> W3 可观测性与告警闭环
  -> W4 高可用与灾备
  -> W5 支付、退款与通知
  -> W6 预生产容量基线
```

依赖说明：

- W0 必须最先完成，否则后续实验基线不稳定。
- W2、W3 可以在 W1 完成后并行开发，但验收报告应分别生成。
- W4 依赖 W3 的故障时间线和指标采集，否则无法准确计算 RTO/MTTD/MTTR。
- W5 会改变业务状态机和流量结构，因此 W6 必须最后执行。

## 4. W0：当前观测改动收口

### 已有基础

- 已增加 Redis Lua、MySQL CAS、Hikari、JVM、线程池和容器资源采集。
- 已完成热点 1000、多订单 1000×10 的 Redis 开/关诊断矩阵。
- 当前诊断报告明确说明“尚未证明性能优化完成”。

### 执行任务

- [ ] 审查当前工作区的全部改动，确认没有混入与观测无关的行为变更。当前工作区同时包含 W1～W5 的业务和验证改动，完整人工 diff 审查留待 Git 交付阶段。
- [x] 执行 `mvn -s mvn-settings.xml clean verify`。
- [x] 执行 Python 脚本语法检查和针对报告生成器的测试。
- [x] 执行 `python scripts/validate_documentation_consistency.py`。
- [x] 执行 `python scripts/validate_repository_safety.py`。
- [x] 执行 `docker compose config` 和 `docker build --tag campus-errand-grab:local-check .`。
- [x] 重新生成运行时诊断报告，确认原始 JSON 索引、报告数字和文档引用一致。
- [x] 将本轮验证日志写入 `reports/runtime/`，记录命令、时间、退出码和代码状态。

### 量化验收

| 指标 | 完成标准 |
| --- | ---: |
| Maven 测试失败/错误/跳过 | 0 / 0 / 0 |
| JaCoCo 指令覆盖率 | 不低于 70% 门禁 |
| JaCoCo 分支覆盖率 | 不低于 45% 门禁 |
| 文档强量化主张证据覆盖率 | 100% |
| 仓库安全扫描发现数 | 0 |
| 报告中的缺失指标误写为 0 | 0 |

### 交付物

```text
reports/runtime/maven-verify-<timestamp>.log
reports/runtime/current-workspace-validation.json
reports/document-consistency.json
reports/benchmarks/runtime-bottleneck-report.md
```

### 完成定义

本轮代码、测试、报告与文档在同一个工作区状态下通过自动化本地验收；完整人工 diff 审查和远程 CI 仍不能由本报告替代。

## 5. W1：Redis 路径性能优化

### 当前基线

诊断矩阵已经完成 1 次预热和 5 次正式运行：

| 场景 | Redis 开启/关闭中位 RPS | Redis 开启/关闭中位 P99 | 关键候选项 |
| --- | ---: | ---: | --- |
| 热点 1000 | 150.78 / 148.90 | 1910.75 / 2028.27 ms | Lua P99 22.00 ms、Hikari pending 峰值 3 |
| 多订单 1000×10 | 535.20 / 542.55 | 615.98 / 621.18 ms | Lua P99 1.30 ms、Hikari pending 峰值 0、应用 CPU 较高 |

该结果说明新版诊断中热点场景已无明显负向结果，多订单场景的 RPS 差异为 -1.36%；但目前仍不能断言已找到单一瓶颈。

### 执行任务

- [x] 从原始样本统计 min/median/max、离散程度和每轮资源峰值，判断差异是否大于运行波动。
- [ ] 为 Redis 客户端连接池增加 active/idle/pending 或等价指标；当前报告仍将该项标为未采集。
- [x] 固定场景、数据量、JVM、Hikari、身份数、Worker 数和容器环境，完成受控开关对照。
- [ ] 分别试验 Redis 连接池大小、命令超时、客户端线程数和 Lua 调用路径，每轮只改变一个变量。
- [x] 对热点 1000 和多订单 1000×10 各执行 1 次预热、5 次正式运行。
- [ ] 对最佳候选配置再次复跑，排除偶然波动；当前没有判定为最佳的优化配置。
- [x] 自动验证 HTTP 5xx 为 0、单赢家、五项对账和数据库 CAS 降幅没有回退。
- [x] 将无改善的参数实验写入机器可读索引和报告。

本轮结论为 `no_stable_improvement_observed`：Redis 保留了显著降低 DB CAS 压力的价值，但不能据此声称通用 RPS 优化。完整数据见 `reports/benchmarks/redis-tuning-report.md` 和 `reports/benchmarks/runtime-bottleneck-report.md`。

### 决策规则

- 若优化后差异小于运行波动，结论写为“未观察到稳定性能改善”，保留 Redis 的数据库减压价值。
- 若吞吐或延迟改善但 CAS 降幅、单赢家或资金不变量回退，则实验判定失败。
- 若开启 Redis 仅在特定竞争密度下有收益，应按场景启用或记录适用边界，不能推广为通用结论。

### 量化验收

| 指标 | 完成标准 |
| --- | --- |
| 对照重复次数 | 每个变体 1 次预热 + 至少 5 次正式运行 |
| HTTP 5xx | 每轮为 0 |
| 单赢家与五项对账 | 每轮通过 |
| 数据库 CAS 降幅 | 不低于对应正式基线，或解释差异来源 |
| 性能结论 | 包含中位数、范围、波动和适用场景 |
| 瓶颈结论 | 至少有一个指标能够支持或排除具体假设 |

不预设必须提升多少 RPS。若取得稳定改善，再报告真实百分比。

### 交付物

```text
reports/benchmarks/raw/redis-tuning-*.json
reports/benchmarks/redis-tuning-report.md
reports/benchmarks/charts/redis-tuning-*.svg
```

### 可转化成果模板

> 针对【场景】的 Redis 路径开销，结合 Lua 延迟、连接池等待和 JVM/容器资源定位【原因】，通过【改动】使中位 RPS 从【A】提升至【B】、P99 从【C】降至【D】，同时维持单赢家、HTTP 5xx 为 0 和数据库 CAS 降幅【E】。

## 6. W2：生产安全闭环

### 已有基础

- 已有 `ProductionSecurityValidator`、生产 profile、JWT 当前/旧密钥支持、登录限流、安全审计和 RBAC。
- 已有生产管理端口隔离配置和仓库敏感信息扫描。
- 已有 JWT、管理员权限、普通用户越权和登录限流测试，但没有形成完整生产安全验收报告。

### 执行任务

- [x] 新增生产 profile 启动测试，验证缺少 JWT、数据库、Redis、管理员和支付回调凭据时拒绝启动。
- [x] 通过生产 Compose 静态配置验证管理端口不发布，管理网络只暴露 `health`、`info`、`prometheus`。
- [x] 补齐无身份、USER、ADMIN 三种身份对管理端点的权限矩阵测试。
- [x] 补齐 JWT 当前密钥签发、旧密钥过渡验证和旧密钥下线后的拒绝测试。
- [x] 执行 6 次错误登录模拟，记录 5 次阈值、429 触发和 traceId 返回。
- [x] 在 CI 中加入依赖漏洞扫描、容器镜像扫描、SBOM 生成和 HIGH/CRITICAL 门禁。
- [x] 生成脱敏的安全验收汇总，明确本地 Trivy 未安装和远程 CI 未检查的边界。

本地安全结果为 `PASS`，但依赖扫描为 `PARTIAL`、镜像扫描为 `NOT_RUN`、SBOM 为直接 POM 依赖的 `PARTIAL` 清单；证据见 `reports/security/`。

### 量化验收

| 指标 | 完成标准 |
| --- | ---: |
| 缺失关键凭据的启动拒绝用例 | 100% 通过 |
| 管理端点权限矩阵 | 100% 符合预期 |
| JWT 轮换的 3 个阶段 | 全部通过 |
| 登录攻击达到阈值后的限制触发率 | 100% |
| 仓库中的真实敏感凭据 | 0 |
| 可发布制品中的高危/严重漏洞 | 0，或有带期限的明确豁免记录 |

### 交付物

```text
src/test/java/.../ProductionProfileSecurityIT.java
src/test/java/.../ManagementEndpointSecurityIT.java
reports/security/security-validation.json
reports/security/dependency-scan.json
reports/security/container-scan.json
reports/security/sbom.json
```

### 可转化成果模板

> 建立生产配置、管理端点、JWT 轮换与登录风控的自动化安全回归，覆盖【N】类误配置/越权/攻击场景，并通过 CI 阻断真实密钥泄露和高危依赖或镜像漏洞。

## 7. W3：可观测性、SLO 与告警闭环

### 已有基础

- Prometheus 已采集 10 项核心指标，未发现高基数身份标签。
- Grafana 数据源和三类核心面板可用。
- Outbox DEAD、资金对账失败、延迟队列积压 3 类告警已完成实际触发和恢复验证。
- 目前只有 HTTP 层 traceId，没有跨 HTTP、MySQL、Redis、Outbox 和定时任务的 OpenTelemetry 链路。

### 执行任务

- [ ] 接入 OpenTelemetry SDK/Agent 和本地 Trace 后端。
- [ ] 为 HTTP、MySQL、Redis、Outbox 发布、结算重试创建可关联 span。
- [ ] 设计核心链路 trace 完整性采样与统计脚本；当前 trace coverage 明确记录为 `NOT_RUN`。
- [x] 为抢单、结算、Outbox、对账定义 SLI、窗口和首版 SLO。
- [x] 实际验证 Redis 降级、结算重试耗尽、Outbox DEAD、对账失败和延迟队列积压告警的触发与恢复。
- [ ] 配置真实告警接收路由的测试目标，验证告警发送、恢复通知和去重。
- [x] 为每个当前 P1 告警编写中文 Runbook。
- [x] 故障演练记录发生、检测、修复和恢复时间点；外部告警送达时间点未运行。
- [ ] 计算完整的 MTTD、MTTA 和 MTTR；当前仅有本地 MTTD/MTTR，MTTA 和外部通知未验证。

本地规则触发与恢复验证为 `PASS`；OpenTelemetry 和外部 Alertmanager 路由均为 `NOT_RUN`。证据见 `reports/observability/`、`reports/monitoring/`、`docs/slo.md` 和 `ops/runbooks/`。

### 量化验收

| 指标 | 完成标准 |
| --- | ---: |
| 核心业务链路 trace 完整率 | ≥ 95% |
| 计划内告警触发率 | 100% |
| 告警恢复通知成功率 | 100% |
| P1 告警 Runbook 覆盖率 | 100% |
| 正常观测窗口误报数 | 0，且报告窗口长度 |
| MTTD/MTTA/MTTR | 每类故障均有实测值 |

### 交付物

```text
ops/otel/*
ops/runbooks/*.md
docs/slo.md
reports/observability/trace-coverage.json
reports/observability/alert-routing-validation.json
reports/observability/incident-timeline-*.json
```

### 可转化成果模板

> 打通 HTTP、MySQL、Redis、Outbox 与结算任务的分布式追踪，核心链路 trace 完整率达到【X】；通过【N】类故障演练验证告警与 Runbook，将实测 MTTD/MTTR 控制在【Y/Z】。

## 8. W4：高可用、备份与恢复演练

### 范围边界

已有 Redis 停止后的业务降级演练只证明单个依赖故障下的正确性，不等于数据库高可用或灾备完成。本任务必须覆盖备份恢复、实例切换和数据追平。

### 执行任务

- [x] 定义订单、账本和 Outbox 的 RPO/RTO 测量口径。
- [x] 编写 MySQL 全量备份脚本，并记录备份时间、大小和校验和。
- [ ] 配置 binlog 或等价增量恢复能力，验证恢复到指定时间点。
- [ ] 在全新 Compose 项目名或独立环境中恢复备份；当前仅恢复到同机 MySQL 的隔离数据库。
- [ ] 恢复后完整执行注册、充值、发单、抢单、送达、结算和五项对账冒烟；当前报告仅证明逻辑备份/恢复步骤通过。
- [x] 验证 Redis 清空或重启后，从 MySQL 重建抢单 marker 与延迟索引。
- [ ] 分别注入 MySQL 不可用、Redis 重启、应用实例终止和网络延迟；当前只完成 Redis 和应用重启。
- [ ] 每类故障至少执行 3 轮；当前 Redis/应用重启各 3 轮，其他故障未执行。
- [x] 生成恢复报告，分别给出订单、账本、Outbox 的 RPO 观察值和 RTO 时间序列。

本地同机演练为 `PASS`，但 W4 总体仍为 `PARTIAL`：未覆盖 MySQL HA、跨主机恢复、持久化 binlog/PITR、网络延迟和并发写入下的 RPO。证据见 `reports/disaster-recovery/disaster-recovery-report.json` 和 `reports/disaster-recovery/disaster-recovery-report.md`。

### 量化验收

| 指标 | 完成标准 |
| --- | ---: |
| 每类故障演练次数 | ≥ 3 |
| 恢复后五项对账异常 | 0 |
| Redis 派生状态重建完整率 | 100% |
| 独立环境业务冒烟 | 100% 通过 |
| RTO | 有每轮实测值、中位数和最大值 |
| RPO | 有订单、账本、Outbox 分类实测值 |
| 备份完整性 | 校验和验证通过 |

### 交付物

```text
scripts/backup_mysql.py
scripts/restore_mysql.py
scripts/rebuild_redis_state.py
scripts/run_disaster_recovery_drill.py
docs/disaster-recovery-runbook.md
reports/disaster-recovery/*.json
reports/disaster-recovery/disaster-recovery-report.md
```

### 可转化成果模板

> 建立 MySQL 全量/增量恢复与 Redis 派生状态重建流程，在【N】轮故障演练中取得恢复成功率【X】、实测 RTO【Y】和 RPO【Z】，恢复后订单、Outbox 与五项资金对账异常为 0。

## 9. W5：支付、退款与通知业务闭环

### 范围边界

现有充值接口和订单取消退款属于内部业务逻辑，不等于真实支付回调和退款渠道已经接入。第一版使用支付沙箱或本地可控 Provider，重点验证签名、幂等、乱序与补偿，不处理真实资金。

### 执行任务

- [x] 定义支付、退款、通知、争议和补偿的状态机与不可逆边界。
- [x] 抽象 `PaymentProvider`，提供本地沙箱实现和可重复回放入口。
- [x] 新增支付回调验签、时间戳/重放窗口和事件唯一键。
- [x] 将支付入账、退款、通知事件和审计记录纳入事务与 Outbox。
- [ ] 完整覆盖渠道超时、通知失败和部分失败补偿；当前已验证重复、乱序、签名错误、过期和补偿幂等。
- [x] 新增争议单、管理员处理和人工补偿记录，禁止直接修改余额绕过账本。
- [x] 对支付回调与退款各执行 1,000 次重复回放。
- [ ] 对乱序、超时和崩溃恢复建立完整批量测试矩阵。
- [x] 回放结束执行支付、退款、通知、补偿和五项资金对账。

本地 SANDBOX 回放为 `PASS`，但 W5 总体仍为 `PARTIAL`：没有联系真实支付/退款渠道，回放为顺序请求，渠道超时和外部通知故障尚未形成完整矩阵。证据见 `reports/payment/payment-replay-report.json` 和 `reports/payment/payment-validation-report.md`。

### 量化验收

| 指标 | 完成标准 |
| --- | ---: |
| 同一支付回调重复回放 | ≥ 1,000 次，仅 1 次有效入账 |
| 同一退款请求重复回放 | ≥ 1,000 次，仅 1 次有效退款 |
| 乱序事件最终状态一致率 | 100% |
| 通知业务重复发送 | 0 |
| 资金对账异常 | 0 |
| 人工补偿审计字段完整率 | 100% |

### 交付物

```text
docs/payment-state-machine.md
src/main/java/.../payment/*
src/main/java/.../notification/*
src/test/java/.../PaymentCallbackIT.java
src/test/java/.../RefundIdempotencyIT.java
scripts/replay_payment_events.py
reports/payment/payment-replay-report.json
reports/payment/payment-validation-report.md
```

### 可转化成果模板

> 设计支付回调、退款、Outbox 与通知的幂等链路，对重复和乱序事件执行【N】次回放，仅产生 1 次有效资金变更，最终状态一致率 100%、资金对账异常为 0。

## 10. W6：预生产容量基线

### 前置条件

- W1 的 Redis 配置和实现已经固定。
- W2 的生产安全配置能够启动并通过验收。
- W3 的指标、追踪和告警可以采集容量测试证据。
- W5 的业务流量模型已经稳定。

### 执行任务

- [ ] 将压测端和被测系统部署到不同主机或隔离运行节点。
- [ ] 固定并记录应用、MySQL、Redis 的 CPU、内存、磁盘、网络和版本。
- [ ] 明确定义最大稳定档位的 SLO，例如 HTTP 5xx、P99、积压和对账约束。
- [ ] 执行阶梯增压，覆盖热点、混合业务、支付回调和持续负载。
- [ ] 每个档位至少重复 5 次，记录中位数、范围和资源峰值。
- [ ] 对候选稳定档位执行 30～60 分钟稳定性测试。
- [ ] 继续提升到首个违反 SLO 的档位，以定位容量拐点和资源瓶颈。
- [ ] 保留超时、错误或对账失败的档位，不从正式报告中删除。
- [ ] 生成容量曲线、资源曲线和适用范围说明。

### 量化验收

| 指标 | 完成标准 |
| --- | --- |
| 压测与服务隔离 | 不在同一资源竞争域 |
| 每个负载档位重复次数 | ≥ 5 |
| 稳定性测试时长 | 30～60 分钟 |
| 最大稳定 RPS | 满足预先定义的 P99、5xx、积压和对账约束 |
| 饱和点 | 有首个失败档位和瓶颈指标证据 |
| 正确性 | 单赢家及五项资金对账全部通过 |

### 交付物

```text
reports/capacity/environment.md
reports/capacity/raw/*.json
reports/capacity/capacity-baseline.md
reports/capacity/charts/*.svg
```

### 可转化成果模板

> 在固定【资源规格】和独立压测端环境下建立预生产容量基线，在 P99≤【阈值】、HTTP 5xx≤【阈值】及资金对账异常为 0 的条件下稳定处理【RPS】，并定位容量拐点为【瓶颈】。

## 11. 建议里程碑

| 里程碑 | 包含任务 | 完成标志 | 建议顺序 |
| --- | --- | --- | ---: |
| M1：可信基线 | W0 | 当前工作区完整验证通过，报告可重现 | 1 |
| M2：性能案例 | W1 | 瓶颈得到证据支持，完成优化或形成明确 trade-off | 2 |
| M3：安全可运维 | W2、W3 | 安全门禁、链路追踪、SLO、告警路由和 Runbook 通过 | 3 |
| M4：可恢复 | W4 | 实测 RTO/RPO，独立环境恢复和对账通过 | 4 |
| M5：业务闭环 | W5 | 支付/退款/通知重复与乱序回放通过 | 5 |
| M6：容量证据 | W6 | 固定资源下测得稳定容量与饱和点 | 6 |

## 12. 每轮执行记录模板

每完成一个任务，在对应报告中填写：

```markdown
### 本轮工作

- 问题：
- 变更：
- 个人负责范围：

### 实验口径

- 代码状态：
- 环境与资源：
- 基线组：
- 实验组：
- 预热与重复次数：

### 量化结果

- RPS/P95/P99：
- HTTP 5xx：
- 单赢家：
- DB CAS/Redis 过滤：
- 五项资金对账：
- 故障检测/恢复时间：

### 结论与边界

- 支持的结论：
- 不支持的结论：
- 失败样本：
- 原始证据路径：
```

## 13. 总体验收清单

- [x] W0 当前观测改动的本地自动化验收收口；完整人工 diff 审查和远程 CI 仍待后续。
- [ ] W1 Redis 性能瓶颈定位和优化复测完成。
- [ ] W2 生产安全自动化验收完成。
- [ ] W3 OpenTelemetry、SLO、告警路由和 Runbook 完成。
- [ ] W4 MySQL/Redis 备份、恢复和 RTO/RPO 演练完成。
- [ ] W5 支付、退款、通知、争议和补偿闭环完成。
- [ ] W6 独立压测端的预生产容量基线完成。
- [x] 所有新增强主张均能回指原始 JSON、测试报告或本地验收记录。
- [x] README、项目简历、HANDOFF 和计划文档同步为当前证据状态。

只有对应验收项全部完成后，才能把工作包中的成果模板改写为简历中的已达成结果。
