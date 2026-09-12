# Redis Degraded Runbook

触发条件：`CampusErrandRedisDegraded`，最近 1 分钟 Redis 降级计数增加并持续 1 分钟。

1. 确认 MySQL CAS 仍是最终裁决，检查 HTTP 5xx、赢家数量和 CAS 增量。
2. 检查 Redis 容器、连接错误和 `redis_lua_failure_total`，避免在持有数据库行锁时等待 Redis。
3. 恢复 Redis 后执行状态重建，并用新订单验证 marker、延迟索引和抢单路径。
4. 运行对账并记录降级窗口的 RTO、请求数、数据库 CAS 和五项不变量。
