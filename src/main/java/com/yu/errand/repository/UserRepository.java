package com.yu.errand.repository;

import com.yu.errand.domain.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long insert(String nickname, String username, String passwordHash) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO t_user(nickname, username, password_hash, role) VALUES (?, ?, ?, 'USER')", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, nickname);
            ps.setString(2, username);
            ps.setString(3, passwordHash);
            return ps;
        }, holder);
        return holder.getKey().longValue();
    }

    public long insertAdmin(String nickname, String username, String passwordHash) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO t_user(nickname, username, password_hash, role) VALUES (?, ?, ?, 'ADMIN')",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, nickname);
            ps.setString(2, username);
            ps.setString(3, passwordHash);
            return ps;
        }, holder);
        return holder.getKey().longValue();
    }

    public Optional<User> find(long id) {
        return jdbc.query("SELECT id,nickname,role,username FROM t_user WHERE id=?", rs ->
                rs.next() ? Optional.of(new User(rs.getLong("id"), rs.getString("nickname"), rs.getString("role"), rs.getString("username")))
                        : Optional.empty(), id);
    }

    public Optional<User> findByUsername(String username) {
        return jdbc.query("SELECT id,nickname,role,username FROM t_user WHERE username=?", rs ->
                rs.next() ? Optional.of(new User(rs.getLong("id"), rs.getString("nickname"), rs.getString("role"), rs.getString("username")))
                        : Optional.empty(), username);
    }

    public String passwordHash(long id) {
        return jdbc.queryForObject("SELECT password_hash FROM t_user WHERE id=?", String.class, id);
    }

    public boolean updateRole(long id, String role) {
        return jdbc.update("UPDATE t_user SET role=? WHERE id=?", role, id) == 1;
    }

    public boolean exists(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM t_user WHERE id=?)", Boolean.class, id));
    }
}
