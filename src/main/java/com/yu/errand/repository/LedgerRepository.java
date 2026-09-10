package com.yu.errand.repository;

import com.yu.errand.domain.AccountType;
import com.yu.errand.domain.model.LedgerEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class LedgerRepository {
    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(String bizType, long bizId, long userId, AccountType accountType, long amountCents) {
        jdbc.update("INSERT INTO t_ledger_entry(biz_type,biz_id,user_id,account_type,amount_cents) VALUES (?,?,?,?,?)",
                bizType, bizId, userId, accountType.name(), amountCents);
    }

    public List<LedgerEntry> findByUser(long userId) {
        return jdbc.query("SELECT id,biz_type,biz_id,user_id,account_type,amount_cents,created_at " +
                        "FROM t_ledger_entry WHERE user_id=? ORDER BY id DESC LIMIT 100",
                (rs, row) -> new LedgerEntry(rs.getLong("id"), rs.getString("biz_type"), rs.getLong("biz_id"),
                        rs.getLong("user_id"), AccountType.valueOf(rs.getString("account_type")), rs.getLong("amount_cents"),
                        timestamp(rs.getTimestamp("created_at"))), userId);
    }

    public long total() {
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(amount_cents),0) FROM t_ledger_entry", Long.class);
        return value == null ? 0L : value;
    }

    public long countForBiz(String bizType, long bizId) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM t_ledger_entry WHERE biz_type=? AND biz_id=?", Long.class, bizType, bizId);
        return value == null ? 0L : value;
    }

    private static LocalDateTime timestamp(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}

