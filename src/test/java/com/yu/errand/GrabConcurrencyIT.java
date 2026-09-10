package com.yu.errand;

import com.yu.errand.controller.dto.GrabResult;
import com.yu.errand.domain.model.ErrandOrder;
import com.yu.errand.service.GrabService;
import com.yu.errand.service.OrderService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrabConcurrencyIT extends IntegrationTestBase {
    @Autowired private OrderService orders;
    @Autowired private GrabService grab;

    @Test
    void fiftyConcurrentRequestsProduceExactlyOneWinner() throws Exception {
        long publisher = user("concurrency-publisher");
        recharge(publisher, 100_000, "grab-concurrency-recharge");
        long taker = user("concurrency-taker");
        long orderId = orders.create(publisher, "one order", null, 1000, 120L).id();
        int clients = 50;
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        List<Boolean> winners = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    GrabResult result = grab.grab(orderId, taker);
                    synchronized (winners) { winners.add(result.won()); }
                } catch (RuntimeException ex) {
                    synchronized (errors) { errors.add(ex); }
                }
                return null;
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        while (!pool.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) { }
        assertEquals(1, winners.stream().filter(Boolean::booleanValue).count());
        assertEquals(clients - 1, errors.size());
        ErrandOrder order = orders.get(orderId);
        assertEquals("TAKEN", order.status().name());
        assertEquals(taker, order.takerId());
    }
}

