package com.yu.errand.repository;

import com.yu.errand.domain.model.Dispute;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;

@Repository
public class DisputeRepository {
    private final JdbcTemplate jdbc;

    public DisputeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Dispute insert(long paymentId, long userId, String reason) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO t_dispute(payment_id,user_id,reason) VALUES (?,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, paymentId);
            statement.setLong(2, userId);
            statement.setString(3, reason);
            return statement;
        }, holder);
        return find(holder.getKey().longValue()).orElseThrow();
    }

    public Optional<Dispute> find(long id) {
        return jdbc.query("SELECT id,payment_id,user_id,status,reason,resolution,resolved_by FROM t_dispute WHERE id=?",
                rs -> rs.next() ? Optional.of(new Dispute(rs.getLong("id"), rs.getLong("payment_id"),
                        rs.getLong("user_id"), rs.getString("status"), rs.getString("reason"),
                        rs.getString("resolution"), (Long) rs.getObject("resolved_by"))) : Optional.empty(), id);
    }

    public int resolve(long id, long adminId, String resolution) {
        return jdbc.update("UPDATE t_dispute SET status='RESOLVED',resolved_by=?,resolution=?,resolved_at=NOW(3) WHERE id=? AND status='OPEN'",
                adminId, resolution, id);
    }
}
