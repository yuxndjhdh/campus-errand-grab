# Outbox DEAD Runbook

触发条件：`CampusErrandOutboxDead`，`outbox_dead > 0` 持续 1 分钟。

1. 查看 `GET /api/admin/outbox/dead`，记录事件 ID、类型、重试次数和 `last_error`。
2. 确认对应订单状态、账本和对账结果；先修复根因，不直接修改余额。
3. 使用 `POST /api/admin/outbox/{id}/replay` 重放单条事件。
4. 观察 `outbox_dead`、`outbox_pending`、Redis 派生键和五项资金不变量。
5. 在事件时间线中记录发生、检测、定位、处理、恢复和验证时间。

回滚边界：如果重放再次失败，保留 DEAD 事件和日志，不重复人工入账。
