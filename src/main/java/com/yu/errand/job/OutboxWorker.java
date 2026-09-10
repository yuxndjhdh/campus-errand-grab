package com.yu.errand.job;

import com.yu.errand.domain.OrderStatus;
import com.yu.errand.domain.model.ErrandOrder;
import com.yu.errand.domain.model.OutboxEvent;
import com.yu.errand.redis.DelayQueue;
import com.yu.errand.redis.GrabMarker;
import com.yu.errand.repository.OutboxRepository;
import com.yu.errand.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class OutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final int MAX_RETRIES = 12;
    private final OutboxRepository outbox;
    private final OrderService orders;
    private final GrabMarker marker;
    private final DelayQueue delayQueue;

    public OutboxWorker(OutboxRepository outbox, OrderService orders, GrabMarker marker, DelayQueue delayQueue) {
        this.outbox = outbox;
        this.orders = orders;
        this.marker = marker;
        this.delayQueue = delayQueue;
    }

    @Transactional
    @Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms:500}", initialDelay = 1000)
    public void publish() {
        List<OutboxEvent> events = claimBatch();
        for (OutboxEvent event : events) {
            try {
                publish(event);
                outbox.markPublished(event.id());
            } catch (RuntimeException ex) {
                int retry = event.retryCount() + 1;
                if (retry >= MAX_RETRIES) {
                    outbox.markDead(event.id(), retry, ex.toString());
                    log.error("outbox event moved to DEAD eventId={} type={} bizId={} retries={}",
                            event.id(), event.eventType(), event.bizId(), retry, ex);
                } else {
                    outbox.markRetry(event.id(), retry, LocalDateTime.now().plus(backoff(retry)), ex.toString());
                    log.warn("outbox event failed eventId={} type={} bizId={} retry={}",
                            event.id(), event.eventType(), event.bizId(), retry, ex);
                }
            }
        }
    }

    protected List<OutboxEvent> claimBatch() {
        LocalDateTime now = LocalDateTime.now();
        return outbox.claimBatch(50, now, now.plusSeconds(30));
    }

    private void publish(OutboxEvent event) {
        ErrandOrder order = orders.get(event.bizId());
        switch (event.eventType()) {
            case "ORDER_PUBLISHED" -> {
                if (order.status() == OrderStatus.PUBLISHED) {
                    marker.armStrict(order.id(), order.claimDeadlineAt(), order.publisherId());
                    delayQueue.addClaimStrict(order.id(), order.claimDeadlineAt());
                }
            }
            case "ORDER_TAKEN" -> {
                if (order.status() == OrderStatus.TAKEN) {
                    delayQueue.addDeliverStrict(order.id(), order.deliverDeadlineAt());
                }
            }
            case "ORDER_TERMINAL" -> {
                marker.removeStrict(order.id());
                delayQueue.removeStrict(DelayQueue.CLAIM_KEY, "CLAIM:" + order.id());
                delayQueue.removeStrict(DelayQueue.DELIVER_KEY, "DELIVER:" + order.id());
            }
            default -> throw new IllegalArgumentException("unknown outbox event type: " + event.eventType());
        }
    }

    private static Duration backoff(int retry) {
        long seconds = Math.min(300, 1L << Math.min(retry - 1, 8));
        return Duration.ofSeconds(seconds);
    }
}
