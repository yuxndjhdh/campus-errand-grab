package com.yu.errand.repository;

import com.yu.errand.domain.OrderStatus;
import com.yu.errand.domain.model.ErrandOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepository {
    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long insert(long publisherId, String title, String detail, long rewardCents, long claimTtlSeconds) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO t_errand_order(publisher_id,title,detail,reward_cents,status,claim_deadline_at) " +
                            "VALUES (?,?,?,?, 'PUBLISHED', DATE_ADD(NOW(3), INTERVAL ? SECOND))",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, publisherId);
            ps.setString(2, title);
            ps.setString(3, detail);
            ps.setLong(4, rewardCents);
            ps.setLong(5, claimTtlSeconds);
            return ps;
        }, holder);
        return holder.getKey().longValue();
    }

    public Optional<ErrandOrder> find(long id) {
        List<ErrandOrder> rows = jdbc.query(selectSql() + " WHERE o.id=?", this::map, id);
        return rows.stream().findFirst();
    }

    public Optional<ErrandOrder> findForUpdate(long id) {
        List<ErrandOrder> rows = jdbc.query(selectSql() + " WHERE o.id=? FOR UPDATE", this::map, id);
        return rows.stream().findFirst();
    }

    public List<ErrandOrder> list(int limit, int offset) {
        return jdbc.query(selectSql() + " ORDER BY o.id DESC LIMIT ? OFFSET ?", this::map, limit, offset);
    }

    public int claim(long orderId, long takerId, long deliverTtlSeconds) {
        return jdbc.update("UPDATE t_errand_order SET status='TAKEN',taker_id=?,grabbed_at=NOW(3)," +
                        "deliver_deadline_at=DATE_ADD(NOW(3), INTERVAL ? SECOND),version=version+1 " +
                        "WHERE id=? AND status='PUBLISHED' AND taker_id IS NULL AND publisher_id<>? AND claim_deadline_at>NOW(3)",
                takerId, deliverTtlSeconds, orderId, takerId);
    }

    public boolean claimExpired(long orderId) {
        Boolean expired = jdbc.queryForObject("SELECT claim_deadline_at<=NOW(3) FROM t_errand_order WHERE id=?", Boolean.class, orderId);
        return Boolean.TRUE.equals(expired);
    }

    public int markDelivered(long orderId, long takerId) {
        return jdbc.update("UPDATE t_errand_order SET status='DELIVERED',delivered_at=NOW(3),settlement_next_retry_at=NOW(3),version=version+1 " +
                        "WHERE id=? AND status='TAKEN' AND taker_id=? AND deliver_deadline_at>NOW(3)", orderId, takerId);
    }

    public int markSettled(long orderId, long commissionCents) {
        return jdbc.update("UPDATE t_errand_order SET status='SETTLED',commission_cents=?,settled_at=NOW(3),settlement_next_retry_at=NULL,settlement_last_error=NULL,version=version+1 " +
                        "WHERE id=? AND status='DELIVERED'", commissionCents, orderId);
    }

    public int cancelByPublisher(long orderId, long publisherId) {
        return jdbc.update("UPDATE t_errand_order SET status='CANCELLED',cancelled_at=NOW(3),version=version+1 " +
                        "WHERE id=? AND publisher_id=? AND status IN ('PUBLISHED','TAKEN')", orderId, publisherId);
    }

    public int timeoutClaim(long orderId) {
        return jdbc.update("UPDATE t_errand_order SET status='CANCELLED',cancelled_at=NOW(3),version=version+1 " +
                        "WHERE id=? AND status='PUBLISHED' AND claim_deadline_at<=NOW(3)", orderId);
    }

    public int timeoutDeliver(long orderId) {
        return jdbc.update("UPDATE t_errand_order SET status='CANCELLED',cancelled_at=NOW(3),version=version+1 " +
                        "WHERE id=? AND status='TAKEN' AND deliver_deadline_at<=NOW(3)", orderId);
    }

    public List<Long> dueClaimIds(int limit) {
        return jdbc.query("SELECT id FROM t_errand_order WHERE status='PUBLISHED' AND claim_deadline_at<=NOW(3) ORDER BY id LIMIT ?",
                (rs, row) -> rs.getLong(1), limit);
    }

    public List<Long> dueDeliverIds(int limit) {
        return jdbc.query("SELECT id FROM t_errand_order WHERE status='TAKEN' AND deliver_deadline_at<=NOW(3) ORDER BY id LIMIT ?",
                (rs, row) -> rs.getLong(1), limit);
    }

    public List<Long> activePublishedIds(int limit) {
        return jdbc.query("SELECT id FROM t_errand_order WHERE status='PUBLISHED' AND claim_deadline_at>NOW(3) ORDER BY id LIMIT ?",
                (rs, row) -> rs.getLong(1), limit);
    }

    public List<Long> activePublishedIdsAfter(long cursor, int limit) {
        return jdbc.query("SELECT id FROM t_errand_order WHERE status='PUBLISHED' AND claim_deadline_at>NOW(3) AND id>? ORDER BY id LIMIT ?",
                (rs, row) -> rs.getLong(1), cursor, limit);
    }

    public List<ErrandOrder> deliveredForRetry(int limit, int maxRetries) {
        return jdbc.query(selectSql() + " WHERE o.status='DELIVERED' AND o.settlement_dead=0 AND o.settlement_retry_count<? " +
                "AND (o.settlement_next_retry_at IS NULL OR o.settlement_next_retry_at<=NOW(3)) " +
                "AND o.delivered_at < DATE_SUB(NOW(3), INTERVAL 1 SECOND) ORDER BY o.id LIMIT ?", this::map, maxRetries, limit);
    }

    public int recordSettlementFailure(long orderId, int retryCount, LocalDateTime nextRetryAt, String error) {
        return jdbc.update("UPDATE t_errand_order SET settlement_retry_count=?,settlement_next_retry_at=?,settlement_last_error=? WHERE id=? AND status='DELIVERED'",
                retryCount, Timestamp.valueOf(nextRetryAt), truncate(error), orderId);
    }

    public int markSettlementDead(long orderId, String error) {
        return jdbc.update("UPDATE t_errand_order SET settlement_dead=1,settlement_last_error=? WHERE id=? AND status='DELIVERED'",
                truncate(error), orderId);
    }

    public long countByStatus(OrderStatus status) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM t_errand_order WHERE status=?", Long.class, status.name());
        return value == null ? 0L : value;
    }

    public long countSettlementDead() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM t_errand_order WHERE settlement_dead=1", Long.class);
        return value == null ? 0L : value;
    }

    private String selectSql() {
        return "SELECT o.id,o.publisher_id,o.taker_id,o.title,o.detail,o.reward_cents,o.commission_cents,o.status," +
                "o.claim_deadline_at,o.deliver_deadline_at,o.grabbed_at,o.delivered_at,o.settled_at,o.cancelled_at," +
                "o.version,o.created_at,o.settlement_retry_count,o.settlement_next_retry_at,o.settlement_last_error,o.settlement_dead " +
                "FROM t_errand_order o";
    }

    private ErrandOrder map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ErrandOrder(rs.getLong("id"), rs.getLong("publisher_id"),
                rs.getObject("taker_id", Long.class), rs.getString("title"), rs.getString("detail"),
                rs.getLong("reward_cents"), rs.getLong("commission_cents"), OrderStatus.valueOf(rs.getString("status")),
                dt(rs.getTimestamp("claim_deadline_at")), dt(rs.getTimestamp("deliver_deadline_at")),
                dt(rs.getTimestamp("grabbed_at")), dt(rs.getTimestamp("delivered_at")),
                dt(rs.getTimestamp("settled_at")), dt(rs.getTimestamp("cancelled_at")),
                rs.getLong("version"), dt(rs.getTimestamp("created_at")), rs.getInt("settlement_retry_count"),
                dt(rs.getTimestamp("settlement_next_retry_at")), rs.getString("settlement_last_error"), rs.getBoolean("settlement_dead"));
    }

    private static String truncate(String value) {
        return value == null ? null : value.substring(0, Math.min(1000, value.length()));
    }

    private static LocalDateTime dt(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
