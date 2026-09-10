package com.yu.errand.repository;

import com.yu.errand.domain.AccountType;
import com.yu.errand.domain.model.Account;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AccountRepository {
    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void ensureAccounts(long userId) {
        jdbc.update("INSERT IGNORE INTO t_account(user_id,account_type,balance_cents) VALUES (?, 'AVAILABLE', 0), (?, 'FROZEN', 0)", userId, userId);
    }

    public List<Account> findByUserId(long userId) {
        return jdbc.query("SELECT user_id,account_type,balance_cents FROM t_account WHERE user_id=? ORDER BY account_type",
                (rs, row) -> new Account(rs.getLong("user_id"), AccountType.valueOf(rs.getString("account_type")), rs.getLong("balance_cents")), userId);
    }

    public long balance(long userId, AccountType type) {
        Long value = jdbc.queryForObject("SELECT balance_cents FROM t_account WHERE user_id=? AND account_type=?", Long.class, userId, type.name());
        return value == null ? 0L : value;
    }

    public int change(long userId, AccountType type, long delta) {
        return jdbc.update("UPDATE t_account SET balance_cents=balance_cents+?, version=version+1 WHERE user_id=? AND account_type=?",
                delta, userId, type.name());
    }

    public int debitAvailable(long userId, long amount) {
        return jdbc.update("UPDATE t_account SET balance_cents=balance_cents-?, version=version+1 " +
                        "WHERE user_id=? AND account_type='AVAILABLE' AND balance_cents>=?",
                amount, userId, amount);
    }

    public int debitFrozen(long userId, long amount) {
        return jdbc.update("UPDATE t_account SET balance_cents=balance_cents-?, version=version+1 " +
                        "WHERE user_id=? AND account_type='FROZEN' AND balance_cents>=?",
                amount, userId, amount);
    }

    public void lockUsers(long... userIds) {
        if (userIds.length == 0) return;
        String placeholders = java.util.stream.LongStream.of(userIds).mapToObj(v -> "?").collect(java.util.stream.Collectors.joining(","));
        jdbc.query("SELECT user_id,account_type FROM t_account WHERE user_id IN (" + placeholders + ") ORDER BY user_id,account_type FOR UPDATE",
                ps -> { for (int i = 0; i < userIds.length; i++) ps.setLong(i + 1, userIds[i]); },
                (rs, row) -> rs.getLong(1));
    }

    public long sumFrozen() {
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(balance_cents),0) FROM t_account WHERE account_type='FROZEN'", Long.class);
        return value == null ? 0L : value;
    }

    public List<Account> all() {
        return jdbc.query("SELECT user_id,account_type,balance_cents FROM t_account ORDER BY user_id,account_type",
                (rs, row) -> new Account(rs.getLong("user_id"), AccountType.valueOf(rs.getString("account_type")), rs.getLong("balance_cents")));
    }
}
