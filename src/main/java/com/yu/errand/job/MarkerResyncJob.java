package com.yu.errand.job;

import com.yu.errand.redis.GrabMarker;
import com.yu.errand.repository.OrderRepository;
import com.yu.errand.service.OrderService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Component
public class MarkerResyncJob {
    private static final Logger log = LoggerFactory.getLogger(MarkerResyncJob.class);
    private final OrderRepository orders;
    private final OrderService orderService;
    private final GrabMarker marker;

    public MarkerResyncJob(OrderRepository orders, OrderService orderService, GrabMarker marker) {
        this.orders = orders;
        this.orderService = orderService;
        this.marker = marker;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 1500)
    public void resync() {
        long cursor = 0;
        while (true) {
            List<Long> ids = orders.activePublishedIdsAfter(cursor, 500);
            if (ids.isEmpty()) return;
            ids.forEach(id -> {
                try {
                    var order = orderService.get(id);
                    marker.rearm(id, order.claimDeadlineAt(), order.publisherId());
                } catch (RuntimeException ex) {
                    log.warn("marker resync failed orderId={}", id, ex);
                }
            });
            cursor = ids.get(ids.size() - 1);
        }
    }
}
