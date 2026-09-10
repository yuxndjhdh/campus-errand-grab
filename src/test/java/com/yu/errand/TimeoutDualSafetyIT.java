package com.yu.errand;

import com.yu.errand.job.MarkerResyncJob;
import com.yu.errand.service.GrabService;
import com.yu.errand.service.OrderService;
import com.yu.errand.service.SettlementService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.yu.errand.common.BizException;

class TimeoutDualSafetyIT extends IntegrationTestBase {
    @Autowired private OrderService orders;
    @Autowired private GrabService grab;
    @Autowired private SettlementService settlement;
    @Autowired private MarkerResyncJob markerResync;

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
    }

    @Test
    void deliveryAndTimeoutProduceOneTerminalPath() throws Exception {
        long publisher = user("race-delivery-publisher");
        recharge(publisher, 10_000, "race-delivery-recharge");
        long taker = user("race-delivery-taker");
        long orderId = orders.create(publisher, "delivery race", null, 1000, 120L).id();
        grab.grab(orderId, taker);
        jdbc.update("UPDATE t_errand_order SET deliver_deadline_at=DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id=?", orderId);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var deliver = pool.submit(() -> { try { orders.deliver(orderId, taker, settlement); } catch (RuntimeException ignored) { } });
        var timeout = pool.submit(() -> orders.timeoutDeliver(orderId));
        deliver.get();
        timeout.get();
        pool.shutdown();
        assertTrue(java.util.Set.of("CANCELLED", "SETTLED", "DELIVERED").contains(orders.get(orderId).status().name()));
        assertEquals(0L, jdbc.queryForObject("SELECT COALESCE(SUM(amount_cents),0) FROM t_ledger_entry", Long.class));
    }
}
