package com.yu.errand.repository;

import com.yu.errand.domain.model.OutboxEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(String eventType, long bizId, String payloadJson) {
        jdbc.update("INSERT INTO t_outbox_event(event_type,biz_id,payload_json) VALUES (?,?,?)",
                eventType, bizId, payloadJson);
    }

    public List<OutboxEvent> claimBatch(int limit, LocalDateTime now, LocalDateTime lockUntil) {
        List<OutboxEvent> events = jdbc.query(
                "SELECT id,event_type,biz_id,payload_json,status,retry_count,next_retry_at,locked_until,last_error,created_at " +
                        "FROM t_outbox_event WHERE ((status IN ('PENDING','RETRY') AND next_retry_at<=?) " +
                        "OR (status='PROCESSING' AND locked_until<=?)) ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                this::map, Timestamp.valueOf(now), Timestamp.valueOf(now), limit);
        if (events.isEmpty()) return events;
        for (OutboxEvent event : events) {
            jdbc.update("UPDATE t_outbox_event SET status='PROCESSING',locked_until=? WHERE id=?",
                    Timestamp.valueOf(lockUntil), event.id());
        }
        return events;
    }

    public int markPublished(long id) {
        return jdbc.update("UPDATE t_outbox_event SET status='PUBLISHED',published_at=NOW(3),locked_until=NULL,last_error=NULL WHERE id=?", id);
    }

    public int markRetry(long id, int retryCount, LocalDateTime nextRetryAt, String error) {
        return jdbc.update("UPDATE t_outbox_event SET status='RETRY',retry_count=?,next_retry_at=?,locked_until=NULL,last_error=? WHERE id=?",
                retryCount, Timestamp.valueOf(nextRetryAt), truncate(error), id);
    }

    public int markDead(long id, int retryCount, String error) {
        return jdbc.update("UPDATE t_outbox_event SET status='DEAD',retry_count=?,locked_until=NULL,last_error=? WHERE id=?",
                retryCount, truncate(error), id);
    }

    public long countByStatus(String status) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox_event WHERE status=?", Long.class, status);
        return count == null ? 0L : count;
    }

    public List<OutboxEvent> dead(int limit) {
        return jdbc.query("SELECT id,event_type,biz_id,payload_json,status,retry_count,next_retry_at,locked_until,last_error,created_at " +
                        "FROM t_outbox_event WHERE status='DEAD' ORDER BY id DESC LIMIT ?", this::map, limit);
    }

    public int replay(long id) {
        return jdbc.update("UPDATE t_outbox_event SET status='PENDING',retry_count=0,next_retry_at=NOW(3),locked_until=NULL,last_error=NULL WHERE id=? AND status='DEAD'", id);
    }

    private OutboxEvent map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new OutboxEvent(rs.getLong("id"), rs.getString("event_type"), rs.getLong("biz_id"),
                rs.getString("payload_json"), rs.getString("status"), rs.getInt("retry_count"),
                dt(rs.getTimestamp("next_retry_at")), dt(rs.getTimestamp("locked_until")),
                rs.getString("last_error"), dt(rs.getTimestamp("created_at")));
    }

    private static LocalDateTime dt(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
