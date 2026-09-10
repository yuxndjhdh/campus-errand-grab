package com.yu.errand.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
public class ReconRepository {
    private final JdbcTemplate jdbc;

    public ReconRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long ledgerTotal() {
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(amount_cents),0) FROM t_ledger_entry", Long.class);
        return value == null ? 0L : value;
    }

    public long accountLedgerMismatches() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM (" +
                "SELECT a.user_id,a.account_type,a.balance_cents,COALESCE(SUM(l.amount_cents),0) ledger_total " +
                "FROM t_account a LEFT JOIN t_ledger_entry l ON l.user_id=a.user_id AND l.account_type=a.account_type " +
                "GROUP BY a.user_id,a.account_type,a.balance_cents " +
                "HAVING a.balance_cents<>COALESCE(SUM(l.amount_cents),0)) x", Long.class);
        return value == null ? 0L : value;
    }

    public long escrowMismatch() {
        Long value = jdbc.queryForObject("SELECT ABS((SELECT COALESCE(SUM(balance_cents),0) FROM t_account WHERE account_type='FROZEN') - " +
                "(SELECT COALESCE(SUM(reward_cents),0) FROM t_errand_order WHERE status IN ('PUBLISHED','TAKEN','DELIVERED'))) > 0", Long.class);
        return Boolean.TRUE.equals(value != null && value != 0L) ? 1L : 0L;
    }

    public long illegalNegativeAccounts() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM t_account a JOIN t_user u ON u.id=a.user_id " +
                "WHERE u.role<>'MINT' AND a.balance_cents<0", Long.class);
        return value == null ? 0L : value;
    }

    public long overdueOpenOrders() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM t_errand_order WHERE " +
                "(status='PUBLISHED' AND claim_deadline_at<=NOW(3)) OR (status='TAKEN' AND deliver_deadline_at<=NOW(3))", Long.class);
        return value == null ? 0L : value;
    }

    public void saveReport(boolean passed, String json) {
        jdbc.update("INSERT INTO t_recon_report(passed,report_json) VALUES (?,?)", passed, json);
    }

    public String latestReport() {
        return jdbc.query("SELECT report_json FROM t_recon_report ORDER BY id DESC LIMIT 1", rs -> rs.next() ? rs.getString(1) : null);
    }
}

