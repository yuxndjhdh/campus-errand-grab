# Reconciliation Failure Runbook

触发条件：`CampusErrandReconFailure`，最近 1 分钟存在失败对账并持续 1 分钟。

1. 保存 `POST /api/admin/recon/run` 的脱敏结果和 `X-Trace-Id`。
2. 按 INV-1 至 INV-5 定位差异，区分账本、物化账户、托管订单和超时订单。
3. 禁止直接更新余额掩盖差异；使用原子业务补偿或恢复流程。
4. 修复后重新运行对账，确认 `recon_failure_total` 不再增长且所有不变量为 true。
5. 记录 MTTD、MTTA、MTTR 和每轮原始报告路径。
