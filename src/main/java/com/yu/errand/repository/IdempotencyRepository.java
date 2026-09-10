package com.yu.errand.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class IdempotencyRepository {
    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean tryInsert(String key, String type, long bizId) {
        return start(key, type, bizId, null) == IdempotencyState.NEW;
    }

    public IdempotencyState start(String key, String type, long bizId, String requestHash) {
        try {
            jdbc.update("INSERT INTO t_idempotent_op(op_key,op_type,biz_id,request_hash) VALUES (?,?,?,?)",
                    key, type, bizId, requestHash);
            return IdempotencyState.NEW;
        } catch (DuplicateKeyException duplicate) {
            IdempotencyRecord existing = find(key).orElseThrow(() -> duplicate);
            if (requestHash != null && existing.requestHash() != null && !requestHash.equals(existing.requestHash())) {
                throw new com.yu.errand.common.BizException(com.yu.errand.common.ErrorCode.IDEMPOTENCY_CONFLICT,
                        "idempotency key was reused with different request parameters");
            }
            return IdempotencyState.REPLAY;
        }
    }

    public Optional<IdempotencyRecord> find(String key) {
        return jdbc.query("SELECT op_key,op_type,biz_id,request_hash,response_json FROM t_idempotent_op WHERE op_key=?",
                rs -> rs.next() ? Optional.of(new IdempotencyRecord(rs.getString("op_key"), rs.getString("op_type"),
                        rs.getLong("biz_id"), rs.getString("request_hash"), rs.getString("response_json"))) : Optional.empty(), key);
    }

    public int complete(String key, String responseJson) {
        return jdbc.update("UPDATE t_idempotent_op SET response_json=? WHERE op_key=?", responseJson, key);
    }
}
