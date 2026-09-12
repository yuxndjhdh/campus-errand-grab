# HTTP 5xx Runbook

触发条件：`CampusErrandHttp5xxHigh`，5xx 比例超过 5% 持续 5 分钟。

1. 按 traceId 聚合应用日志，区分依赖故障、验证错误和未处理异常。
2. 检查数据库连接等待、Redis Lua 失败、Outbox 和 JVM/容器资源。
3. 先恢复依赖或回滚最近变更，再验证健康检查和核心业务不变量。
4. 记录告警触发、通知送达、定位、修复和恢复时间。
