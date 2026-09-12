package com.yu.errand.monitoring;

import com.yu.errand.repository.OrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.List;

@Component
public class ReliabilityMetrics {
    private final MeterRegistry registry;
    private final Counter redisDegradedCounter;
    private final Counter outboxPublishSuccessCounter;
    private final Counter outboxPublishFailureCounter;
    private final Timer outboxPublishTimer;
    private final ConcurrentMap<String, Counter> redisLuaCallCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> redisLuaSuccessCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> redisLuaFailureCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> redisLuaTimers = new ConcurrentHashMap<>();
    private final AtomicLong redisLuaInFlight = new AtomicLong();
    private final AtomicLong redisLuaConcurrencyMax = new AtomicLong();
    private final AtomicLong timeoutQueueLagMillis = new AtomicLong();

    public ReliabilityMetrics(MeterRegistry registry, OrderRepository orders) {
        this.registry = registry;
        redisDegradedCounter = Counter.builder("redis_degraded_total")
                .description("Redis operations that fell back to a database-safe path")
                .register(registry);
        outboxPublishSuccessCounter = Counter.builder("outbox_publish_success_total")
                .description("Outbox events whose Redis side effects and lease update completed")
                .register(registry);
        outboxPublishFailureCounter = Counter.builder("outbox_publish_failure_total")
                .description("Outbox events that failed during side-effect publishing")
                .register(registry);
        outboxPublishTimer = Timer.builder("outbox_publish_duration")
                .description("Time spent publishing one outbox event")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        Gauge.builder("timeout_queue_lag_seconds", timeoutQueueLagMillis, value -> value.get() / 1000.0)
                .description("Oldest observed due timeout event lag")
                .register(registry);
        Gauge.builder("settlement_dead_total", orders, OrderRepository::countSettlementDead)
                .description("Orders permanently marked as settlement dead")
                .register(registry);
        Gauge.builder("redis_lua_in_flight", redisLuaInFlight, AtomicLong::get)
                .description("Redis Lua calls currently waiting on the client")
                .register(registry);
        Gauge.builder("redis_lua_concurrency_max", redisLuaConcurrencyMax, AtomicLong::get)
                .description("Maximum concurrent Redis Lua calls observed since process start")
                .register(registry);
        for (String script : List.of("grab_filter", "grab_marker_rearm", "grab_rate_limit", "login_attempt")) {
            redisLuaCallCounters.put(script, redisLuaCalls(script));
            redisLuaSuccessCounters.put(script, redisLuaSuccesses(script));
            redisLuaFailureCounters.put(script, redisLuaFailures(script));
            redisLuaTimers.put(script, redisLuaTimer(script));
        }
    }

    public void redisDegraded() { redisDegradedCounter.increment(); }

    /**
     * Start one Redis Lua observation. Script names are fixed internal values,
     * never user or order identifiers, so the metric remains low cardinality.
     */
    public long startRedisLua(String script) {
        redisLuaCallCounters.computeIfAbsent(script, this::redisLuaCalls).increment();
        long inFlight = redisLuaInFlight.incrementAndGet();
        redisLuaConcurrencyMax.accumulateAndGet(inFlight, Math::max);
        return System.nanoTime();
    }

    public void redisLuaSucceeded(String script, long startedNanos) {
        redisLuaSuccessCounters.computeIfAbsent(script, this::redisLuaSuccesses).increment();
        redisLuaTimers.computeIfAbsent(script, this::redisLuaTimer)
                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
        redisLuaInFlight.decrementAndGet();
    }

    public void redisLuaFailed(String script, long startedNanos) {
        redisLuaFailureCounters.computeIfAbsent(script, this::redisLuaFailures).increment();
        redisLuaTimers.computeIfAbsent(script, this::redisLuaTimer)
                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
        redisLuaInFlight.decrementAndGet();
    }

    public void outboxPublishSucceeded() { outboxPublishSuccessCounter.increment(); }
    public void outboxPublishFailed() { outboxPublishFailureCounter.increment(); }
    public void recordOutboxPublishDuration(long startedNanos) {
        outboxPublishTimer.record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }
    public void setTimeoutQueueLagMillis(long lagMillis) {
        timeoutQueueLagMillis.set(Math.max(0L, lagMillis));
    }

    private Counter redisLuaCalls(String script) {
        return Counter.builder("redis_lua_calls_total")
                .description("Redis Lua invocations by fixed script name")
                .tag("script", script)
                .register(registry);
    }

    private Counter redisLuaSuccesses(String script) {
        return Counter.builder("redis_lua_success_total")
                .description("Redis Lua invocations completed without a Redis client error")
                .tag("script", script)
                .register(registry);
    }

    private Counter redisLuaFailures(String script) {
        return Counter.builder("redis_lua_failure_total")
                .description("Redis Lua invocations that raised a Redis client error")
                .tag("script", script)
                .register(registry);
    }

    private Timer redisLuaTimer(String script) {
        return Timer.builder("redis_lua_duration")
                .description("Redis Lua execution and client round-trip duration")
                .tag("script", script)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }
}
