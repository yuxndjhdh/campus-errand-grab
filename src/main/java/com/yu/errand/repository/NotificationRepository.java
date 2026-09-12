package com.yu.errand.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;

@Repository
public class NotificationRepository {
    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(String eventKey, long userId, String type, String payload) {
        jdbc.update("INSERT INTO t_notification(event_key,user_id,notification_type,payload_json) VALUES (?,?,?,?)",
                eventKey, userId, type, payload);
    }

    public long lastId(String eventKey) {
        Long id = jdbc.queryForObject("SELECT id FROM t_notification WHERE event_key=?", Long.class, eventKey);
        if (id == null) throw new IllegalStateException("notification was not inserted");
        return id;
    }

    public int markSent(long id) {
        return jdbc.update("UPDATE t_notification SET status='SENT',attempts=attempts+1,sent_at=NOW(3),last_error=NULL WHERE id=? AND status<>'SENT'", id);
    }

    public long countByEventKey(String eventKey) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM t_notification WHERE event_key=?", Long.class, eventKey);
        return count == null ? 0 : count;
    }

    public String statusByEventKey(String eventKey) {
        return jdbc.queryForObject("SELECT status FROM t_notification WHERE event_key=?", String.class, eventKey);
    }
}
