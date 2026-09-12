# Timeout Queue Lag Runbook

触发条件：`CampusErrandTimeoutQueueLagHigh`，`timeout_queue_lag_seconds > 30` 持续 2 分钟。

1. 检查 Redis `delay:claim` 和 `delay:deliver` 的最早 score，以及应用定时任务线程池。
2. 判断是 Redis 不可用、应用实例停止、任务线程饥饿还是数据重建遗漏。
3. 恢复依赖后执行 `scripts/rebuild_redis_state.py`，再观察队列是否追平。
4. 对受影响订单执行对账，确认没有越过状态机或资金边界。
5. 记录故障发生、检测、修复和队列恢复时间。
