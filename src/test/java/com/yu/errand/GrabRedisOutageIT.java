package com.yu.errand;

import com.yu.errand.common.BizException;
import com.yu.errand.controller.dto.GrabResult;
import com.yu.errand.domain.model.ErrandOrder;
import com.yu.errand.job.OutboxWorker;
import com.yu.errand.job.MarkerResyncJob;
import com.yu.errand.service.GrabService;
import com.yu.errand.service.OrderService;
import com.yu.errand.service.ReconService;
import com.yu.errand.service.SettlementService;
import com.yu.errand.support.RedisProxyIntegrationTestBase;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrabRedisOutageIT extends RedisProxyIntegrationTestBase {
    @Autowired private OrderService orders;
    @Autowired private GrabService grab;
    @Autowired private SettlementService settlement;
    @Autowired private ReconService recon;
    @Autowired private MarkerResyncJob markerResync;
    @Autowired private OutboxWorker outboxWorker;

    @Test
    void redisDisconnectStillProducesExactlyOneWinner() throws Exception {
        long publisher = user("proxy-outage-publisher");
        recharge(publisher, 100_000, "proxy-outage-recharge");
        List<Long> takers = new ArrayList<>();
        for (int i = 0; i < 16; i++) takers.add(user("proxy-outage-taker-" + i));
        long orderId = orders.create(publisher, "redis outage", null, 1000, 120L).id();
        assertTrue(Boolean.TRUE.equals(redis.hasKey("grab:stock:" + orderId)));

        REDIS_PROXY.setConnectionCut(true);
        ExecutorService pool = Executors.newFixedThreadPool(takers.size());
        CountDownLatch ready = new CountDownLatch(takers.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Long> winners = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        try {
            for (long taker : takers) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        GrabResult result = grab.grab(orderId, taker);
                        if (result.won()) synchronized (winners) { winners.add(taker); }
                    } catch (Throwable ex) {
                        synchronized (errors) { errors.add(ex); }
                    }
                    return null;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            REDIS_PROXY.setConnectionCut(false);
            pool.shutdownNow();
        }

        assertEquals(1, winners.size());
        assertEquals(takers.size() - 1, errors.size());
        assertTrue(errors.stream().allMatch(error -> error instanceof BizException), "Redis outage leaked a system error");
        ErrandOrder taken = orders.get(orderId);
        assertEquals("TAKEN", taken.status().name());
        assertEquals(winners.get(0), taken.takerId());
        orders.deliver(orderId, winners.get(0), settlement);
        assertEquals("SETTLED", orders.get(orderId).status().name());
        assertTrue(recon.run().passed());
    }

    @Test
    void redisTimeoutFailsOpenWithinConfiguredDeadline() {
        long publisher = user("proxy-latency-publisher");
        recharge(publisher, 10_000, "proxy-latency-recharge");
        long taker = user("proxy-latency-taker");
        long orderId = orders.create(publisher, "redis latency", null, 1000, 120L).id();
        var toxic = addLatencyToxic();
        try {
            assertTimeout(Duration.ofSeconds(2), () -> assertTrue(grab.grab(orderId, taker).won()));
        } finally {
            try {
                toxic.remove();
            } catch (Exception ignored) {
                // The container teardown is the fallback cleanup path.
            }
        }
        assertEquals(taker, orders.get(orderId).takerId());
    }

    @Test
    void redisRecoveryRebuildsDerivedState() {
        long publisher = user("proxy-recovery-publisher");
        recharge(publisher, 10_000, "proxy-recovery-recharge");
        REDIS_PROXY.setConnectionCut(true);
        long orderId;
        try {
            orderId = orders.create(publisher, "redis recovery", null, 1000, 120L).id();
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }

        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        markerResync.resync();
        outboxWorker.publish();

        assertTrue(Boolean.TRUE.equals(redis.hasKey("grab:stock:" + orderId)));
        assertNotNull(redis.opsForZSet().score("delay:claim", "CLAIM:" + orderId));
        assertEquals("PUBLISHED", orders.get(orderId).status().name());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_outbox_event WHERE status IN ('PENDING','RETRY','PROCESSING')", Long.class));

        long recoveredTaker = user("proxy-recovery-taker");
        long recoveredOrderId = orders.create(publisher, "redis recovery after", null, 1000, 120L).id();
        assertTrue(grab.grab(recoveredOrderId, recoveredTaker).won());
        orders.deliver(recoveredOrderId, recoveredTaker, settlement);
        assertTrue(recon.run().passed());
    }

    private eu.rekawek.toxiproxy.model.Toxic addLatencyToxic() {
        try {
            return REDIS_PROXY.toxics().latency("redis-high-latency", ToxicDirection.DOWNSTREAM, 1000);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot add Redis latency toxic", ex);
        }
    }
}
