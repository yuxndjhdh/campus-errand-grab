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
        metrics.outboxPublishSucceeded();
        metrics.outboxPublishFailed();
        metrics.recordOutboxPublishDuration(System.nanoTime() - 1_000_000);
        metrics.setTimeoutQueueLagMillis(1_500);

        String scrape = registry.scrape();
        assertTrue(scrape.contains("redis_degraded_total 1.0"));
        assertTrue(scrape.contains("outbox_publish_success_total 1.0"));
        assertTrue(scrape.contains("outbox_publish_failure_total 1.0"));
        assertTrue(scrape.contains("outbox_publish_duration_seconds_count 1"));
        assertTrue(scrape.contains("timeout_queue_lag_seconds 1.5"));
    }
}
