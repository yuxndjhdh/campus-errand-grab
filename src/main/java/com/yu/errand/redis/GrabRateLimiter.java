package com.yu.errand.redis;

import com.yu.errand.config.GrabProperties;
import com.yu.errand.monitoring.ReliabilityMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GrabRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(GrabRateLimiter.class);
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script;
    private final GrabProperties properties;
    private final ReliabilityMetrics metrics;

    public GrabRateLimiter(StringRedisTemplate redis, DefaultRedisScript<Long> grabRateLimitScript,
                           GrabProperties properties, ReliabilityMetrics metrics) {
        this.redis = redis;
        this.script = grabRateLimitScript;
        this.properties = properties;
        this.metrics = metrics;
    }

    public boolean allow(long userId, long orderId) {
        if (!properties.getGrab().isRateLimitEnabled()) return true;
        long startedNanos = metrics.startRedisLua("grab_rate_limit");
        try {
            Long result = redis.execute(script,
                    List.of("rate:grab:user:" + userId, "rate:grab:order:" + orderId),
                    String.valueOf(properties.getGrab().getRateLimitPerUser()),
                    String.valueOf(properties.getGrab().getRateLimitPerOrder()),
                    String.valueOf(properties.getGrab().getRateLimitWindowSeconds()));
            metrics.redisLuaSucceeded("grab_rate_limit", startedNanos);
            return result == null || result == 1L;
        } catch (DataAccessException ex) {
            metrics.redisLuaFailed("grab_rate_limit", startedNanos);
            metrics.redisDegraded();
            log.warn("redis rate limiter unavailable; allowing request userId={} orderId={}", userId, orderId);
            return true;
        }
    }
}
