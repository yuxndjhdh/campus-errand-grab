package com.yu.errand.repository;

import com.yu.errand.domain.model.Refund;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;

@Repository
public class RefundRepository {
    private final JdbcTemplate jdbc;

    public RefundRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Refund insert(long paymentId, String requestKey, long userId, long amountCents) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO t_refund(payment_id,request_key,user_id,amount_cents,status) VALUES (?,?,?,?, 'SUCCEEDED')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, paymentId);
            statement.setString(2, requestKey);
            statement.setLong(3, userId);
            statement.setLong(4, amountCents);
            return statement;
        }, holder);
        return findById(holder.getKey().longValue()).orElseThrow();
    }

    public Optional<Refund> findById(long id) { return find("WHERE id=?", id); }
    public Optional<Refund> findByRequestKey(String requestKey) { return find("WHERE request_key=?", requestKey); }
    public Optional<Refund> findByPaymentId(long paymentId) { return find("WHERE payment_id=?", paymentId); }

    private Optional<Refund> find(String where, Object... args) {
        return jdbc.query("SELECT id,payment_id,request_key,user_id,amount_cents,status FROM t_refund " + where,
                rs -> rs.next() ? Optional.of(new Refund(rs.getLong("id"), rs.getLong("payment_id"),
                        rs.getString("request_key"), rs.getLong("user_id"), rs.getLong("amount_cents"),
                        rs.getString("status"))) : Optional.empty(), args);
    }
}
