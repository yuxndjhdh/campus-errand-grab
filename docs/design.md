# Design Notes

## 状态机

| from | 事件 | to | 数据库守卫 |
| --- | --- | --- | --- |
| - | 发单 | PUBLISHED | AVAILABLE 余额条件扣减成功 |
| PUBLISHED | 抢单 | TAKEN | 未被抢、未过截止时间、不能抢自己的单 |
| PUBLISHED | 领单超时 | CANCELLED | `claim_deadline_at <= NOW(3)` |
| TAKEN | 送达 | DELIVERED | `taker_id = :userId AND deliver_deadline_at > NOW(3)` |
| TAKEN | 送达超时 | CANCELLED | `deliver_deadline_at <= NOW(3)` |
| DELIVERED | 结算 | SETTLED | 幂等键插入成功 + 状态 CAS |
| PUBLISHED/TAKEN | 发布者取消 | CANCELLED | `publisher_id = :userId` |

终态是 SETTLED 和 CANCELLED。每条流转的判据和写入在同一条条件 UPDATE 中，避免 Java 先读后写的 TOCTOU 窗口。

## Redis 的边界

抢单 Lua 脚本原子消费 `grab:stock:{orderId}`。marker 值保存截止时间和发布者 ID，自抢直接返回而不消费有效 marker；已消费 marker 直接返回统一冲突，不再查询订单。Redis 重启、FLUSHDB、超时或断连时，服务回退到 MySQL CAS，`MarkerResyncJob` 和 Outbox Worker 从数据库重建标记。过滤器只减少数据库请求，不决定赢家。

延迟队列 `delay:claim` 和 `delay:deliver` 只负责及时触发；订单行里的截止时间才是真相。ZSet 丢失、worker 宕机或两个 worker 同时处理，都由数据库 CAS 保证最终只生效一次。

## 资金流

金额用分的 `long`。发单写两条 FREEZE 分录：发布者 AVAILABLE -reward，发布者 FROZEN +reward。结算写三条 SETTLE 分录：发布者 FROZEN -reward，跑腿人 AVAILABLE +(reward-commission)，平台 AVAILABLE +commission。佣金按 `floor(reward * rate)`，跑腿人金额由差额计算，所以三条分录构造上求和为零。

结算事务先锁定涉及用户的账户行（按 user_id 排序），再插入幂等键、更新账户、CAS 订单并写分录。唯一键 `uk_biz_account` 是绕过服务层时的最后一道保护。

## 对账

`ReconService` 每分钟运行，也可通过管理员 JWT 调用 `/api/admin/recon/run`。报告会写入 `t_recon_report`，而不是只打日志。用裸 SQL 改坏一个余额时，INV-2 会报告具体失败；检测器必须能被故意打坏的测试验证。

## 事务 Outbox

订单事务写入 `ORDER_PUBLISHED`、`ORDER_TAKEN` 或 `ORDER_TERMINAL` 事件。Worker 用 `SELECT ... FOR UPDATE SKIP LOCKED` 领取事件，Redis 操作成功后标记 `PUBLISHED`；连接失败会指数退避，超过最大次数进入 `DEAD`，不会阻塞后续事件。Redis 操作本身是幂等的，数据库截止时间和 CAS 仍然是最终兜底。

## 身份与资金边界

所有业务用户通过 BCrypt 密码摘要和短期 HMAC JWT 登录。Controller 从认证上下文读取当前用户，系统账户不能发布或抢单；管理员角色只开放对账、统计和测试充值入口。充值的幂等记录保存规范化参数的 SHA-256 指纹，重复 key 但不同金额或用户会被拒绝。
