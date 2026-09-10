package com.yu.errand.monitoring;

import com.yu.errand.repository.OrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ReliabilityMetrics {
    private final Counter redisDegradedCounter;
    private final Counter outboxPublishSuccessCounter;
    private final Counter outboxPublishFailureCounter;
    private final Timer outboxPublishTimer;
    private final AtomicLong timeoutQueueLagMillis = new AtomicLong();

    public ReliabilityMetrics(MeterRegistry registry, OrderRepository orders) {
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
    }

    public void redisDegraded() { redisDegradedCounter.increment(); }
    public void outboxPublishSucceeded() { outboxPublishSuccessCounter.increment(); }
    public void outboxPublishFailed() { outboxPublishFailureCounter.increment(); }
    public void recordOutboxPublishDuration(long startedNanos) {
        outboxPublishTimer.record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }
    public void setTimeoutQueueLagMillis(long lagMillis) {
        timeoutQueueLagMillis.set(Math.max(0L, lagMillis));
    }
}
