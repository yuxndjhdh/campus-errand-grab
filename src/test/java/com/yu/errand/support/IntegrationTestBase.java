package com.yu.errand.support;

import com.yu.errand.domain.AccountType;
import com.yu.errand.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
public abstract class IntegrationTestBase {
    @Container
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("campus_errand_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("app.security.jwt-secret", () -> "test-secret-that-is-long-enough-for-tests");
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserService users;
    @Autowired protected StringRedisTemplate redis;

    @BeforeEach
    void resetProjectData() {
        jdbc.update("DELETE FROM t_ledger_entry");
        jdbc.update("DELETE FROM t_idempotent_op");
        jdbc.update("DELETE FROM t_outbox_event");
        jdbc.update("DELETE FROM t_errand_order");
        jdbc.update("DELETE FROM t_recon_report");
        jdbc.update("DELETE FROM t_account WHERE user_id > 2");
        jdbc.update("DELETE FROM t_user WHERE id > 2");
        jdbc.update("UPDATE t_account SET balance_cents=0,version=0");
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    protected long user(String nickname) { return users.create(nickname).id(); }
    protected void recharge(long id, long cents, String key) { users.recharge(id, cents, key); }
}
