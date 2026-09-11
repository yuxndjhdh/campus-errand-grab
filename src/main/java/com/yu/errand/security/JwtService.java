package com.yu.errand.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.errand.config.GrabProperties;
import com.yu.errand.domain.model.User;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class JwtService {
    private static final String SIGNING_ALGORITHM = "HS256";
    private static final Set<String> SUPPORTED_ROLES = Set.of("USER", "ADMIN");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final GrabProperties properties;
    private final ObjectMapper objectMapper;

    public JwtService(GrabProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String issue(User user) {
        long expiresAt = Instant.now().plusSeconds(properties.getSecurity().getAccessTokenTtlSeconds()).getEpochSecond();
        Map<String, Object> header = Map.of("alg", SIGNING_ALGORITHM, "typ", "JWT");
        Map<String, Object> claims = Map.of("sub", user.username(), "role", user.role(), "exp", expiresAt);
        try {
            String encodedHeader = encode(header);
            String encodedClaims = encode(claims);
            return encodedHeader + "." + encodedClaims + "."
                    + sign(encodedHeader + "." + encodedClaims, properties.getSecurity().getJwtSecret());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot create access token", ex);
        }
    }

    public Optional<TokenClaims> parse(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) return Optional.empty();
            String signingInput = parts[0] + "." + parts[1];
            JsonNode header = objectMapper.readTree(DECODER.decode(parts[0]));
            if (!header.isObject() || !SIGNING_ALGORITHM.equals(header.path("alg").asText())
                    || !"JWT".equals(header.path("typ").asText())) {
                return Optional.empty();
            }
            if (!matchesSignature(parts[2], signingInput)) {
                return Optional.empty();
            }
            JsonNode claims = objectMapper.readTree(DECODER.decode(parts[1]));
            if (!claims.has("sub") || !claims.get("sub").isTextual() || !claims.has("role") ||
                    !claims.get("role").isTextual() || !SUPPORTED_ROLES.contains(claims.get("role").asText())
                    || !claims.has("exp") || !claims.get("exp").canConvertToLong()) return Optional.empty();
            if (claims.get("exp").asLong() <= Instant.now().getEpochSecond()) return Optional.empty();
            return Optional.of(new TokenClaims(claims.get("sub").asText(), claims.get("role").asText(), claims.get("exp").asLong()));
        } catch (RuntimeException | java.io.IOException ex) {
            return Optional.empty();
        }
    }

    private String encode(Map<String, Object> value) throws JsonProcessingException {
        return ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
    }

    private boolean matchesSignature(String encodedSignature, String input) {
        if (sameSignature(encodedSignature, sign(input, properties.getSecurity().getJwtSecret()))) return true;
        String previous = properties.getSecurity().getPreviousJwtSecret();
        return previous != null && !previous.isBlank() && sameSignature(encodedSignature, sign(input, previous));
    }

    private boolean sameSignature(String actual, String expected) {
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII));
    }

    private String sign(String input, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception ex) {
            throw new IllegalStateException("cannot sign access token", ex);
        }
    }

    public record TokenClaims(String subject, String role, long expiresAt) {}
}
