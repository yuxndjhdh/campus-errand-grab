package com.yu.errand.job;

import com.yu.errand.repository.OrderRepository;
import com.yu.errand.service.OrderService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class TimeoutScanJob {
    private static final Logger log = LoggerFactory.getLogger(TimeoutScanJob.class);
    private final OrderRepository orders;
    private final OrderService orderService;

    public TimeoutScanJob(OrderRepository orders, OrderService orderService) {
        this.orders = orders;
        this.orderService = orderService;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 1000)
    public void scan() {
        orders.dueClaimIds(500).forEach(id -> timeout(id, true));
        orders.dueDeliverIds(500).forEach(id -> timeout(id, false));
    }

    private void timeout(long orderId, boolean claim) {
        try {
            if (claim) orderService.timeoutClaim(orderId);
            else orderService.timeoutDeliver(orderId);
        } catch (RuntimeException ex) {
            log.warn("timeout scan failed type={} orderId={}", claim ? "claim" : "deliver", orderId, ex);
        }
    }
}
