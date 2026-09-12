package com.yu.errand.security;

import com.yu.errand.config.GrabProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.boot.SpringApplication;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the production security validator in isolation so missing secrets are
 * rejected before the full application has to connect to external services.
 */
class ProductionProfileSecurityIT {
    private static final String JWT = "production-jwt-secret-0123456789abcdef";
    private static final String DATABASE = "production-db-secret";
    private static final String REDIS = "production-redis-secret";
    private static final String ADMIN = "production-admin-secret";
    private static final String PAYMENT = "production-payment-webhook-secret-012345";

    @Test
    void productionProfileStartsWhenAllRequiredSecretsArePresent() {
        AtomicReference<ConfigurableApplicationContext> context = new AtomicReference<>();
        try {
            assertDoesNotThrow(() -> context.set(start(validProperties()).run(toArguments(validProperties()))));
        } finally {
            close(context);
        }
    }

    @Test
    void missingJwtSecretRejectsProductionStartup() {
        assertStartupRejected(Map.of("app.security.jwt-secret", ""), "JWT_SECRET");
    }

    @Test
    void missingDatabasePasswordRejectsProductionStartup() {
        assertStartupRejected(Map.of("spring.datasource.password", ""), "DB_PASSWORD");
    }

    @Test
    void missingRedisPasswordRejectsProductionStartup() {
        assertStartupRejected(Map.of("spring.data.redis.password", ""), "REDIS_PASSWORD");
    }

    @Test
    void missingAdminPasswordRejectsProductionStartup() {
        assertStartupRejected(Map.of("app.admin.password", ""), "ADMIN_PASSWORD");
    }

    @Test
    void missingPaymentWebhookSecretRejectsProductionStartup() {
        assertStartupRejected(Map.of("app.payment.webhook-secret", ""), "PAYMENT_WEBHOOK_SECRET");
    }

    @Test
    void productionOverlayKeepsManagementEndpointsPrivate() throws Exception {
        String application = Files.readString(Path.of("src/main/resources/application-production.yml"));
        String compose = Files.readString(Path.of("compose.production.yml"));
        assertTrue(application.contains("port: 8081"));
        assertTrue(application.contains("include: health,info,prometheus"));
        assertTrue(compose.contains("SPRING_PROFILES_ACTIVE: production"));
        assertTrue(compose.contains("MANAGEMENT_PORT: \"8081\""));
        assertTrue(compose.contains("mysql:\n    ports: []"));
        assertTrue(compose.contains("redis:\n    ports: []"));
        assertTrue(compose.contains("app:\n    ports: []"));
        assertTrue(compose.contains("prometheus:\n    ports: []"));
        assertTrue(compose.contains("grafana:\n    ports: []"));
    }

    private void assertStartupRejected(Map<String, String> overrides, String expectedSecret) {
        Map<String, String> properties = validProperties();
        properties.putAll(overrides);
        AtomicReference<ConfigurableApplicationContext> context = new AtomicReference<>();
        try {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> context.set(start(properties).run(toArguments(properties))));
            assertTrue(rootMessage(failure).contains(expectedSecret),
                    () -> "startup failure did not identify " + expectedSecret + ": " + rootMessage(failure));
        } finally {
            close(context);
        }
    }

    private SpringApplication start(Map<String, String> properties) {
        SpringApplication application = new SpringApplication(SecurityProbe.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        return application;
    }

    private String[] toArguments(Map<String, String> properties) {
        return properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    private Map<String, String> validProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.profiles.active", "production");
        properties.put("spring.main.web-application-type", "none");
        properties.put("app.security.jwt-secret", JWT);
        properties.put("app.security.require-redis-password", "true");
        properties.put("spring.datasource.password", DATABASE);
        properties.put("spring.data.redis.password", REDIS);
        properties.put("app.admin.password", ADMIN);
        properties.put("app.payment.enabled", "true");
        properties.put("app.payment.webhook-secret", PAYMENT);
        return properties;
    }

    private void close(AtomicReference<ConfigurableApplicationContext> context) {
        ConfigurableApplicationContext applicationContext = context.get();
        if (applicationContext != null) applicationContext.close();
    }

    private String rootMessage(Throwable failure) {
        Throwable current = failure;
        String message = "";
        while (current != null) {
            if (current.getMessage() != null) message += current.getMessage() + "\n";
            current = current.getCause();
        }
        return message;
    }

    @SpringBootConfiguration
    @EnableConfigurationProperties(GrabProperties.class)
    @Import(ProductionSecurityValidator.class)
    static class SecurityProbe { }
}
