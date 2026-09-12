package com.yu.errand.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionSecurityValidatorTest {
    @Test
    void rejectsBlankAndDevelopmentSecrets() {
        assertThrows(IllegalStateException.class, () ->
                ProductionSecurityValidator.requireSecret("JWT_SECRET", "local-development-secret-change-me", 32));
        assertThrows(IllegalStateException.class, () ->
                ProductionSecurityValidator.requireSecret("DB_PASSWORD", "", 12));
        assertThrows(IllegalStateException.class, () ->
                ProductionSecurityValidator.requireSecret("REDIS_PASSWORD", "change-me", 12));
        assertThrows(IllegalStateException.class, () ->
                ProductionSecurityValidator.requireSecret("ADMIN_PASSWORD", "default-admin-password", 12));
        assertThrows(IllegalStateException.class, () ->
                ProductionSecurityValidator.requireSecret("PAYMENT_WEBHOOK_SECRET", "short", 32));
    }

    @Test
    void acceptsAHighEntropySecret() {
        assertDoesNotThrow(() -> ProductionSecurityValidator.requireSecret(
                "JWT_SECRET", "f7b8e9a0c1d2e3f4g5h6i7j8k9l0m1n2", 32));
    }
}
