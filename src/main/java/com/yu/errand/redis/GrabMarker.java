package com.yu.errand.redis;

import com.yu.errand.config.GrabProperties;
import com.yu.errand.monitoring.ReliabilityMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class GrabMarker {
    private static final Logger log = LoggerFactory.getLogger(GrabMarker.class);
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> filterScript;
    private final DefaultRedisScript<Long> rearmScript;
    private final GrabProperties properties;
    private final ReliabilityMetrics metrics;

    public GrabMarker(StringRedisTemplate redis, DefaultRedisScript<Long> grabFilterScript,
                      DefaultRedisScript<Long> grabMarkerRearmScript, GrabProperties properties,
                      ReliabilityMetrics metrics) {
        this.redis = redis;
        this.filterScript = grabFilterScript;
        this.rearmScript = grabMarkerRearmScript;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void arm(long orderId, LocalDateTime deadline) {
        arm(orderId, deadline, 0L);
    }

    public void arm(long orderId, LocalDateTime deadline, long publisherId) {
        try {
            armStrict(orderId, deadline, publisherId);
        } catch (DataAccessException ex) {
            metrics.redisDegraded();
            log.debug("redis marker arm failed for order {}", orderId, ex);
        }
    }

    public void armStrict(long orderId, LocalDateTime deadline, long publisherId) {
        long seconds = Math.max(1, Duration.between(LocalDateTime.now(), deadline).getSeconds() + properties.getGrab().getMarkerTtlSlackSeconds());
        redis.opsForValue().set(markerKey(orderId), markerValue(deadline, publisherId), seconds, TimeUnit.SECONDS);
        redis.opsForValue().set(knownKey(orderId), "1", seconds, TimeUnit.SECONDS);
    }

    public Decision tryPass(long orderId, long nowMillis) {
        return tryPass(orderId, 0L, nowMillis);
    }

    public Decision tryPass(long orderId, long takerId, long nowMillis) {
        long startedNanos = metrics.startRedisLua("grab_filter");
        try {
            Long result = redis.execute(filterScript, List.of(markerKey(orderId), knownKey(orderId)),
                    String.valueOf(nowMillis), String.valueOf(takerId));
            metrics.redisLuaSucceeded("grab_filter", startedNanos);
            return switch (result == null ? -2 : result.intValue()) {
                case 1 -> Decision.PASS;
                case 0 -> Decision.FILTERED;
                case -1 -> Decision.EXPIRED;
                case 2 -> Decision.SELF_GRAB;
                default -> Decision.MISSING;
            };
        } catch (DataAccessException ex) {
            metrics.redisLuaFailed("grab_filter", startedNanos);
            metrics.redisDegraded();
            log.debug("redis marker filter unavailable for order {}", orderId);
            return Decision.UNAVAILABLE;
        }
    }

    public void rearm(long orderId, LocalDateTime deadline) {
        rearm(orderId, deadline, 0L);
    }

    public void rearm(long orderId, LocalDateTime deadline, long publisherId) {
        long startedNanos = metrics.startRedisLua("grab_marker_rearm");
        try {
            long seconds = Math.max(1, Duration.between(LocalDateTime.now(), deadline).getSeconds() + properties.getGrab().getMarkerTtlSlackSeconds());
            redis.execute(rearmScript, List.of(markerKey(orderId), knownKey(orderId)), String.valueOf(toMillis(deadline)),
                    String.valueOf(seconds), String.valueOf(publisherId));
            metrics.redisLuaSucceeded("grab_marker_rearm", startedNanos);
        } catch (DataAccessException ex) {
            metrics.redisLuaFailed("grab_marker_rearm", startedNanos);
            metrics.redisDegraded();
            log.debug("redis marker rearm failed for order {}", orderId, ex);
        }
    }

    public void remove(long orderId) {
        try {
            removeStrict(orderId);
        } catch (DataAccessException ex) {
            metrics.redisDegraded();
            log.debug("redis marker removal failed for order {}", orderId, ex);
        }
    }

    public void removeStrict(long orderId) {
        redis.delete(List.of(markerKey(orderId), knownKey(orderId)));
    }

    private String markerKey(long orderId) { return "grab:stock:" + orderId; }
    private String knownKey(long orderId) { return "grab:known:" + orderId; }

    private static long toMillis(LocalDateTime dateTime) {
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static String markerValue(LocalDateTime deadline, long publisherId) {
        return toMillis(deadline) + "|" + publisherId;
    }

    public enum Decision { PASS, FILTERED, EXPIRED, SELF_GRAB, MISSING, UNAVAILABLE }
}
