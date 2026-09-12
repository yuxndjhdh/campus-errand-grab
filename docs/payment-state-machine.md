# 支付、退款、通知、争议与补偿状态机

## 适用范围

当前实现使用本地 SANDBOX Provider 验证业务边界，不连接真实支付渠道，也不代表真实资金已经入账。MySQL 是支付、退款、通知、争议、补偿和账本的事实来源；Outbox 只负责可靠发布通知副作用。

## 状态与迁移

| 实体 | 初始状态 | 允许迁移 | 不可逆边界 |
| --- | --- | --- | --- |
| Payment | 未见事件 | 验签通过并完成事务后 SETTLED；退款成功后 REFUNDED | REFUNDED 不回到 SETTLED |
| Refund | 未见请求 | 同一请求键首次成功后 SUCCEEDED | 不允许同一 Payment 再创建第二笔退款 |
| Notification | PENDING | Outbox 发布成功后 SENT | SENT 不重复发送 |
| Dispute | OPEN | 管理员补偿成功后 RESOLVED | RESOLVED 不再接受补偿 |
| Compensation | 未见请求键 | 事务插入一条带操作人和原因的记录 | 记录只能追加，不能直接修改余额替代分录 |

Payment 的“未见事件”不是数据库状态，而是唯一键不存在。回调事务先插入 (provider, provider_payment_id, callback_event_id) 唯一记录，再调用内部充值和通知入队；任一步失败都会回滚支付记录与账本变更。

## 幂等与验签规则

1. Provider 先检查 HMAC-SHA256 签名和时间戳窗口，默认允许的时钟偏差为 300 秒；失败请求不能产生业务写入。
2. 同一 callback_event_id 重放时，用户、金额和渠道支付号必须完全一致，否则返回 IDEMPOTENCY_CONFLICT。
3. 同一渠道支付号带来乱序事件时，只保留一条 Payment 和一笔充值；不同事件号不会绕过支付号唯一约束。
4. 充值使用 PAYMENT:<eventId> 作为内部幂等键，支付记录和充值账本在同一事务中提交。
5. 退款要求金额等于整笔沙箱支付金额，使用请求键幂等，并由 uk_refund_payment 保证一个 Payment 最多一笔退款。
6. 通知事件键为 PAYMENT:<paymentId>、REFUND:<refundId> 或 DISPUTE:<disputeId>；通知表和 Outbox 各自使用唯一键，重复发布只更新同一通知的状态。
7. 补偿必须通过 t_ledger_entry 产生 Mint 到用户的复式分录，同时记录 operator_user_id、reason 和请求键。

## 乱序与失败矩阵

| 场景 | 预期结果 | 资金效果 |
| --- | --- | --- |
| 相同回调重复 1,000 次 | 全部返回同一 Payment | 仅一笔充值 |
| 事件 B 先于事件 A 到达 | 两次返回同一 Payment | 仅一笔充值 |
| 相同事件修改金额或用户 | 拒绝并返回冲突 | 无新增分录 |
| 签名错误或时间戳过期 | 返回未授权 | 无业务写入 |
| 相同退款请求重复 1,000 次 | 全部返回同一 Refund | 仅两条退款复式分录 |
| 同一 Payment 使用第二个退款键 | 拒绝非法状态 | 不新增分录 |
| 通知发布失败 | Outbox 重试或进入 DEAD | 不回滚已提交的支付账本 |
| 争议重复补偿 | 首次解析争议，后续返回已解决状态 | 仅一笔补偿复式分录 |

## 验证入口

批量回放使用：

    python scripts/replay_payment_events.py --test-environment --duplicates 1000

脚本会使用唯一测试身份，保留通过/失败计数和对账结果到 reports/payment/payment-replay-report.json，并在结束时清理本轮生成的测试数据。输出不包含 JWT、签名、密码或请求体。

## 当前边界

- Provider 是可控本地沙箱，不是支付宝、微信支付或银行卡渠道。
- 未实现真实渠道退款确认、异步通知重试的外部网络投递和人工工单系统。
- 回放报告证明本地事务、唯一键、Outbox 和账本边界，不证明第三方渠道 SLA 或真实资金结算。
