package com.yu.errand.monitoring;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

@Component("redisOptional")
public class RedisOptionalHealthIndicator implements HealthIndicator {
    private final RedisConnectionFactory connections;

    public RedisOptionalHealthIndicator(RedisConnectionFactory connections) { this.connections = connections; }

    @Override
    public Health health() {
        try {
            String pong = connections.getConnection().ping();
            return "PONG".equalsIgnoreCase(pong) ? Health.up().build() : Health.down().withDetail("ping", pong).build();
        } catch (RuntimeException ex) {
            return Health.down(ex).build();
        }
    }
}
