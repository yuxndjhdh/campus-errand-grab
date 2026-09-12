# Settlement Retry Exhausted Runbook

触发条件：`CampusErrandSettlementRetryExhausted`，`settlement_dead > 0` 持续 1 分钟。

1. 查询对应 `DELIVERED` 订单的 `settlement_retry_count`、`settlement_last_error` 和订单/账本状态。
2. 确认失败原因是依赖不可用、数据异常还是业务状态冲突；不要直接修改订单状态或余额。
3. 修复依赖或数据根因后，先在 disposable 环境重放结算路径并运行五项资金对账。
4. 通过受审计的人工处理流程恢复订单；重复执行必须继续受 `SETTLE:ORDER:<id>` 幂等键保护。
5. 记录触发、检测、定位、修复、恢复和对账验证时间，并保留失败样本。

回滚边界：不能通过直接更新余额清除告警。若无法证明账本、账户和订单状态一致，保留 DEAD 状态并升级人工复核。
