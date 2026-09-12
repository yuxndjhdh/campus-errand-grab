package com.yu.errand.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CompensationRepository {
    private final JdbcTemplate jdbc;

    public CompensationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(long disputeId, long userId, String requestKey, long amountCents, String reason, long operatorId) {
        jdbc.update("INSERT INTO t_compensation(dispute_id,user_id,request_key,amount_cents,reason,operator_user_id) VALUES (?,?,?,?,?,?)",
                disputeId, userId, requestKey, amountCents, reason, operatorId);
    }

    public boolean exists(String requestKey) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM t_compensation WHERE request_key=?", Long.class, requestKey);
        return count != null && count > 0;
    }
}
