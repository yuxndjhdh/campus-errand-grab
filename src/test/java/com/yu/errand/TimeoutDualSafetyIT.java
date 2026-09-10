package com.yu.errand;

import com.yu.errand.job.MarkerResyncJob;
import com.yu.errand.service.GrabService;
import com.yu.errand.service.OrderService;
import com.yu.errand.service.ReconService;
import com.yu.errand.service.SettlementService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.yu.errand.common.BizException;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class TimeoutDualSafetyIT extends IntegrationTestBase {
    @Autowired private OrderService orders;
    @Autowired private GrabService grab;
    @Autowired private SettlementService settlement;
    @Autowired private MarkerResyncJob markerResync;
    @Autowired private ReconService recon;

    @Test
    void databaseFallbackCancelsExpiredPublishedOrder() {
        long publisher = user("timeout-claim-publisher");
        recharge(publisher, 10_000, "timeout-claim-recharge");
        long orderId = orders.create(publisher, "claim timeout", null, 1000, 120L).id();
        jdbc.update("UPDATE t_errand_order SET claim_deadline_at=DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id=?", orderId);
        assertTrue(orders.timeoutClaim(orderId));
        assertEquals("CANCELLED", orders.get(orderId).status().name());
    }

    @Test
    void databaseFallbackCancelsExpiredTakenOrder() {
        long publisher = user("timeout-deliver-publisher");
        recharge(publisher, 10_000, "timeout-deliver-recharge");
        long taker = user("timeout-deliver-taker");
        long orderId = orders.create(publisher, "deliver timeout", null, 1000, 120L).id();
        grab.grab(orderId, taker);
        jdbc.update("UPDATE t_errand_order SET deliver_deadline_at=DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id=?", orderId);
        assertTrue(orders.timeoutDeliver(orderId));
        assertEquals("CANCELLED", orders.get(orderId).status().name());
    }

    @Test
    void markerCanBeRebuiltFromDatabaseAfterRedisFlush() {
        long publisher = user("marker-publisher");
        recharge(publisher, 10_000, "marker-recharge");
        long orderId = orders.create(publisher, "marker", null, 1000, 120L).id();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        markerResync.resync();
        assertTrue(Boolean.TRUE.equals(redis.hasKey("grab:stock:" + orderId)));
    }

    @Test
    void successfulGrabAddsDeliveryTimeoutEvent() {
        long publisher = user("deliver-marker-publisher");
        recharge(publisher, 10_000, "deliver-marker-recharge");
        long taker = user("deliver-marker-taker");
        long orderId = orders.create(publisher, "deliver marker", null, 1000, 120L).id();
        grab.grab(orderId, taker);
        assertTrue(Boolean.TRUE.equals(redis.opsForZSet().score("delay:deliver", "DELIVER:" + orderId) != null));
    }

    @Test
    void deliveryAfterDeadlineReturnsExpired() {
        long publisher = user("expired-delivery-publisher");
        recharge(publisher, 10_000, "expired-delivery-recharge");
        long taker = user("expired-delivery-taker");
        long orderId = orders.create(publisher, "expired delivery", null, 1000, 120L).id();
        grab.grab(orderId, taker);
        jdbc.update("UPDATE t_errand_order SET deliver_deadline_at=DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id=?", orderId);
        BizException failure = assertThrows(BizException.class, () -> orders.deliver(orderId, taker, settlement));
        assertEquals("EXPIRED", failure.errorCode().code());
        assertTrue(orders.timeoutDeliver(orderId));
        assertEquals("CANCELLED", orders.get(orderId).status().name());
    }

    @Test
    void deliveryBeforeDeadlineSettles() {
        long publisher = user("before-deadline-publisher");
        recharge(publisher, 10_000, "before-deadline-recharge");
        long taker = user("before-deadline-taker");
        long orderId = orders.create(publisher, "before deadline", null, 1000, 120L).id();
        grab.grab(orderId, taker);
        jdbc.update("UPDATE t_errand_order SET deliver_deadline_at=DATE_ADD(NOW(3), INTERVAL 2 SECOND) WHERE id=?", orderId);

        orders.deliver(orderId, taker, settlement);

        assertEquals("SETTLED", orders.get(orderId).status().name());
    }

    @Test
    void deliveryAndTimeoutProduceOneTerminalPath() throws Exception {
        long publisher = user("race-delivery-publisher");
        recharge(publisher, 200_000, "race-delivery-recharge");
        long taker = user("race-delivery-taker");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 100; i++) {
                long orderId = orders.create(publisher, "delivery race " + i, null, 1000, 120L).id();
                grab.grab(orderId, taker);
                jdbc.update("UPDATE t_errand_order SET deliver_deadline_at=DATE_ADD(NOW(3), INTERVAL 100 MILLISECOND) WHERE id=?", orderId);

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<?> deliver = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        orders.deliver(orderId, taker, settlement);
                    } catch (RuntimeException ignored) {
                        // The timeout winner is expected to reject delivery.
                    }
                    return null;
                });
                Future<Boolean> timeout = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    Thread.sleep(120);
                    return orders.timeoutDeliver(orderId);
                });
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                deliver.get(5, TimeUnit.SECONDS);
                timeout.get(5, TimeUnit.SECONDS);

                String status = orders.get(orderId).status().name();
                assertTrue(Set.of("SETTLED", "CANCELLED").contains(status), "unexpected terminal status: " + status);
                assertEquals(2L, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_ledger_entry WHERE biz_type='FREEZE' AND biz_id=?", Long.class, orderId));
                long settledEntries = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_ledger_entry WHERE biz_type='SETTLE' AND biz_id=?", Long.class, orderId);
                long cancelledEntries = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_ledger_entry WHERE biz_type='CANCEL' AND biz_id=?", Long.class, orderId);
                assertTrue((settledEntries == 3L && cancelledEntries == 0L)
                                || (settledEntries == 0L && cancelledEntries == 2L),
                        "terminal ledger path was not exclusive for order " + orderId);
                assertEquals(0L, jdbc.queryForObject(
                        "SELECT balance_cents FROM t_account WHERE user_id=? AND account_type='FROZEN'", Long.class, publisher));
                assertTrue(recon.run().passed(), "reconciliation failed for order " + orderId);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
