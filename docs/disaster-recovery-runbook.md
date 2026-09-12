# 灾备与恢复 Runbook

本 Runbook 只覆盖可在本地 disposable Docker Compose 环境复现的逻辑备份、恢复、Redis 派生状态重建和服务重启。它不把单机重启结果描述为数据库高可用、跨主机切换或生产灾备承诺。

## 恢复前保护

1. 确认目标数据库不是 `campus_errand`，恢复目标必须是独立的 disposable 数据库。
2. 保存备份文件的字节数、SHA-256、创建时间和 `mysqldump` 参数。
3. 停止会写入恢复目标的任务，避免恢复过程中混入新数据。
4. 保留失败样本和命令输出，不用成功轮次覆盖失败轮次。

## MySQL 逻辑备份与恢复

```text
python scripts/backup_mysql.py --container campus-errand-grab-mysql-1
python scripts/restore_mysql.py --dump <backup.sql> --container campus-errand-grab-mysql-1 --database campus_errand_restore --replace --test-environment
```

备份使用 `--single-transaction`、触发器、事件、存储过程和十六进制 Blob。恢复脚本强制要求 `--test-environment`，并拒绝覆盖生产数据库名称。

当前本地流程没有配置持久化 binlog 归档和时间点恢复目标，因此 RPO 只能写成逻辑备份/重启演练观察值，不能写成连续复制能力。

## Redis 派生状态重建

Redis 不是订单或资金事实来源。清空或重启 Redis 后执行：

```text
python scripts/rebuild_redis_state.py --mysql-container campus-errand-grab-mysql-1 --redis-container campus-errand-grab-redis-1
```

脚本先清理旧的 `grab:stock:*`、`grab:known:*` marker 和延迟索引，再从 MySQL 的 `PUBLISHED`、`TAKEN`、`DELIVERED` 订单重建派生状态。恢复后必须验证抢单单赢家、订单状态和五项资金对账不变量。

## 故障演练记录

```text
python scripts/run_disaster_recovery_drill.py --test-environment --rounds 3
```

每轮记录 Redis 重启恢复、应用重启恢复、状态重建和总耗时。报告需要同时列出订单、账本、Outbox 的 RPO 观察值，以及当前未覆盖的 MySQL HA、跨主机故障、网络延迟和 binlog 归档边界。

## 恢复后验收

- readiness 和 liveness 健康检查通过。
- 注册、充值、发单、抢单、送达和结算冒烟通过。
- Redis marker 和延迟索引数量与 MySQL 权威订单状态一致。
- `INV-1` 至 `INV-5` 全部为 `true`。
- 记录每轮实测 RTO 中位数和最大值，不把没有并发写入的演练写成生产 RPO 保证。
