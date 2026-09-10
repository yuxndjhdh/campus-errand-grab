package com.yu.errand.monitoring;

import com.yu.errand.repository.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics {
    public OutboxMetrics(OutboxRepository outbox, MeterRegistry registry) {
        Gauge.builder("outbox_pending", outbox,
                        value -> value.countByStatus("PENDING") + value.countByStatus("RETRY") + value.countByStatus("PROCESSING"))
                .register(registry);
        Gauge.builder("outbox_dead", outbox, value -> value.countByStatus("DEAD")).register(registry);
    }
}
