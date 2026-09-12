package com.yu.errand.unit;

import com.yu.errand.monitoring.ReliabilityMetrics;
import com.yu.errand.repository.OrderRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ReliabilityMetricsTest {
    @Test
    void reliabilityMetersUsePrometheusNamesAndRecordValues() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ReliabilityMetrics metrics = new ReliabilityMetrics(registry, mock(OrderRepository.class));

        metrics.redisDegraded();
        long redisLuaStarted = metrics.startRedisLua("grab_filter");
        metrics.redisLuaSucceeded("grab_filter", redisLuaStarted);
        long failedLuaStarted = metrics.startRedisLua("grab_filter");
        metrics.redisLuaFailed("grab_filter", failedLuaStarted);
        metrics.outboxPublishSucceeded();
        metrics.outboxPublishFailed();
        metrics.recordOutboxPublishDuration(System.nanoTime() - 1_000_000);
        metrics.setTimeoutQueueLagMillis(1_500);

        String scrape = registry.scrape();
        assertTrue(scrape.contains("redis_degraded_total 1.0"));
        assertTrue(scrape.contains("redis_lua_calls_total{script=\"grab_filter\"} 2.0"));
        assertTrue(scrape.contains("redis_lua_success_total{script=\"grab_filter\"} 1.0"));
        assertTrue(scrape.contains("redis_lua_failure_total{script=\"grab_filter\"} 1.0"));
        assertTrue(scrape.contains("redis_lua_duration_seconds_count{script=\"grab_filter\"} 2"));
        assertTrue(scrape.contains("redis_lua_in_flight 0.0"));
        assertTrue(scrape.contains("redis_lua_concurrency_max 1.0"));
        assertTrue(scrape.contains("outbox_publish_success_total 1.0"));
        assertTrue(scrape.contains("outbox_publish_failure_total 1.0"));
        assertTrue(scrape.contains("outbox_publish_duration_seconds_count 1"));
        assertTrue(scrape.contains("timeout_queue_lag_seconds 1.5"));
    }
}
