package com.yu.errand.job;

import com.yu.errand.repository.OrderRepository;
import com.yu.errand.service.SettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class SettleRetryJob {
    private static final Logger log = LoggerFactory.getLogger(SettleRetryJob.class);
    private static final int MAX_RETRIES = 12;
    private final OrderRepository orders;
    private final SettlementService settlement;
    private final Counter retryCounter;

    public SettleRetryJob(OrderRepository orders, SettlementService settlement, MeterRegistry registry) {
        this.orders = orders;
        this.settlement = settlement;
        this.retryCounter = Counter.builder("settlement_retry_total").register(registry);
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 5000)
    public void retry() {
        orders.deliveredForRetry(100, MAX_RETRIES).forEach(order -> {
            try {
                settlement.settle(order.id());
            } catch (RuntimeException ex) {
                int retry = order.settlementRetryCount() + 1;
                retryCounter.increment();
                if (retry >= MAX_RETRIES) {
                    orders.markSettlementDead(order.id(), ex.toString());
                    log.error("settlement moved to dead state orderId={} retries={}", order.id(), retry, ex);
                } else {
                    long seconds = Math.min(300, 1L << Math.min(retry - 1, 8));
                    orders.recordSettlementFailure(order.id(), retry, LocalDateTime.now().plusSeconds(seconds), ex.toString());
                    log.warn("settlement retry failed orderId={} retry={} nextRetryInSeconds={}", order.id(), retry, seconds, ex);
                }
            }
        });
    }
}
