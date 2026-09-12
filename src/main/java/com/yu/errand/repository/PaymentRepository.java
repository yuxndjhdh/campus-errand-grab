package com.yu.errand.repository;

import com.yu.errand.domain.model.Payment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;

@Repository
public class PaymentRepository {
    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Payment insert(String provider, String providerPaymentId, String eventId, long userId, long amountCents) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO t_payment(provider,provider_payment_id,callback_event_id,user_id,amount_cents,status) VALUES (?,?,?,?,?,'SETTLED')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, provider);
            statement.setString(2, providerPaymentId);
            statement.setString(3, eventId);
            statement.setLong(4, userId);
            statement.setLong(5, amountCents);
            return statement;
        }, holder);
        return findById(holder.getKey().longValue()).orElseThrow();
    }

    public Optional<Payment> findById(long id) { return find("WHERE id=?", id); }

    public Optional<Payment> findByEvent(String provider, String eventId) {
        return find("WHERE provider=? AND callback_event_id=?", provider, eventId);
    }

    public Optional<Payment> findByProviderId(String provider, String providerPaymentId) {
        return find("WHERE provider=? AND provider_payment_id=?", provider, providerPaymentId);
    }

    public int markRefunded(long id) {
        return jdbc.update("UPDATE t_payment SET status='REFUNDED' WHERE id=? AND status='SETTLED'", id);
    }

    private Optional<Payment> find(String where, Object... args) {
        return jdbc.query("SELECT id,provider,provider_payment_id,callback_event_id,user_id,amount_cents,status FROM t_payment " + where,
                rs -> rs.next() ? Optional.of(new Payment(rs.getLong("id"), rs.getString("provider"),
                        rs.getString("provider_payment_id"), rs.getString("callback_event_id"), rs.getLong("user_id"),
                        rs.getLong("amount_cents"), rs.getString("status"))) : Optional.empty(), args);
    }
}
