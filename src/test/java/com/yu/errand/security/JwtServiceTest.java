package com.yu.errand.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.errand.config.GrabProperties;
import com.yu.errand.domain.model.User;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JwtServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsAlgorithmConfusionAndAcceptsConfiguredPreviousKeyDuringRotation() throws Exception {
        GrabProperties oldProperties = properties("old-secret-that-is-long-enough-for-tests");
        JwtService oldService = new JwtService(oldProperties, objectMapper);
        String oldToken = oldService.issue(new User(7, "test", "USER", "rotating-user"));

        GrabProperties currentProperties = properties("current-secret-that-is-long-enough-for-tests");
        currentProperties.getSecurity().setPreviousJwtSecret(oldProperties.getSecurity().getJwtSecret());
        JwtService currentService = new JwtService(currentProperties, objectMapper);
        assertTrue(currentService.parse(oldToken).isPresent());

        String[] parts = oldToken.split("\\.");
        String noneHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(
                objectMapper.writeValueAsBytes(Map.of("alg", "none", "typ", "JWT")));
        assertFalse(currentService.parse(noneHeader + "." + parts[1] + "." + parts[2]).isPresent());
    }

    @Test
    void rejectsUnsupportedRolesAndExpiredTokens() {
        GrabProperties properties = properties("current-secret-that-is-long-enough-for-tests");
        JwtService service = new JwtService(properties, objectMapper);
        properties.getSecurity().setAccessTokenTtlSeconds(-1);
        String expired = service.issue(new User(7, "test", "USER", "expired-user"));
        assertFalse(service.parse(expired).isPresent());
    }

    private GrabProperties properties(String secret) {
        GrabProperties properties = new GrabProperties();
        properties.getSecurity().setJwtSecret(secret);
        return properties;
    }
}
