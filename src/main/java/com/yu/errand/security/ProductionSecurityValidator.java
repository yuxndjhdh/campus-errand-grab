package com.yu.errand.security;

import com.yu.errand.config.GrabProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
@Profile("production")
public class ProductionSecurityValidator implements ApplicationRunner {
    private final GrabProperties properties;
    private final String databasePassword;
    private final String redisPassword;

    public ProductionSecurityValidator(GrabProperties properties,
                                        @Value("${spring.datasource.password:}") String databasePassword,
                                        @Value("${spring.data.redis.password:}") String redisPassword) {
        this.properties = properties;
        this.databasePassword = databasePassword;
        this.redisPassword = redisPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        requireSecret("JWT_SECRET", properties.getSecurity().getJwtSecret(), 32);
        requireSecret("DB_PASSWORD", databasePassword, 12);
        requireSecret("ADMIN_PASSWORD", properties.getAdmin().getPassword(), 12);
        if (properties.getSecurity().getPreviousJwtSecret() != null
                && !properties.getSecurity().getPreviousJwtSecret().isBlank()) {
            requireSecret("JWT_PREVIOUS_SECRET", properties.getSecurity().getPreviousJwtSecret(), 32);
        }
        if (properties.getSecurity().isRequireRedisPassword()) {
            requireSecret("REDIS_PASSWORD", redisPassword, 12);
        }
    }

    static void requireSecret(String name, String value, int minimumBytes) {
        String normalized = value == null ? "" : value.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.getBytes(StandardCharsets.UTF_8).length < minimumBytes
                || lower.contains("change-me") || lower.contains("replace-") || lower.contains("replace_with")
                || lower.contains("local-development") || lower.contains("default")) {
            throw new IllegalStateException(name + " must be supplied as a high-entropy production secret");
        }
    }
}
