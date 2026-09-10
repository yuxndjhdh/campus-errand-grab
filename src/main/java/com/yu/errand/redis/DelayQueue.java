package com.yu.errand.redis;

import com.yu.errand.monitoring.ReliabilityMetrics;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

@Component
public class DelayQueue {
    public static final String CLAIM_KEY = "delay:claim";
    public static final String DELIVER_KEY = "delay:deliver";
    private final StringRedisTemplate redis;
    private final ReliabilityMetrics metrics;

    public DelayQueue(StringRedisTemplate redis, ReliabilityMetrics metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    public void addClaim(long orderId, LocalDateTime deadline) { add(CLAIM_KEY, "CLAIM:" + orderId, deadline); }
    public void addDeliver(long orderId, LocalDateTime deadline) { add(DELIVER_KEY, "DELIVER:" + orderId, deadline); }
    public void addClaimStrict(long orderId, LocalDateTime deadline) { addStrict(CLAIM_KEY, "CLAIM:" + orderId, deadline); }
    public void addDeliverStrict(long orderId, LocalDateTime deadline) { addStrict(DELIVER_KEY, "DELIVER:" + orderId, deadline); }

    public Set<String> due(String key, double nowMillis, int limit) {
        try {
            Set<String> values = redis.opsForZSet().rangeByScore(key, Double.NEGATIVE_INFINITY, nowMillis, 0, limit);
            return values == null ? Set.of() : values;
        } catch (DataAccessException ex) {
            metrics.redisDegraded();
            return Set.of();
        }
    }

    public Double score(String key, String member) {
        try {
            return redis.opsForZSet().score(key, member);
        } catch (DataAccessException ex) {
            metrics.redisDegraded();
            return null;
        }
    }

    public void remove(String key, String member) {
        try { removeStrict(key, member); } catch (DataAccessException ex) { metrics.redisDegraded(); }
    }

    public void removeStrict(String key, String member) { redis.opsForZSet().remove(key, member); }

    private void add(String key, String member, LocalDateTime deadline) {
        try { addStrict(key, member, deadline); } catch (DataAccessException ex) { metrics.redisDegraded(); }
    }

    private void addStrict(String key, String member, LocalDateTime deadline) {
        redis.opsForZSet().add(key, member, toMillis(deadline));
    }

    private static long toMillis(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
