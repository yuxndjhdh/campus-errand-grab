package com.yu.errand;

import com.yu.errand.service.GrabService;
import com.yu.errand.service.OrderService;
import com.yu.errand.service.SettlementService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettlementIdempotencyIT extends IntegrationTestBase {
    @Autowired private OrderService orders;
    @Autowired private GrabService grab;
    @Autowired private SettlementService settlement;

    @Test
    void twentyConcurrentRetriesOnlySettleOnce() throws Exception {
        long publisher = user("idem-publisher");
        recharge(publisher, 100_000, "idem-recharge");
        long taker = user("idem-taker");
        long orderId = orders.create(publisher, "idem", null, 1000, 120L).id();
        grab.grab(orderId, taker);
        // Deliver without the service callback so all retry calls race on DELIVERED.
        jdbc.update("UPDATE t_errand_order SET status='DELIVERED', delivered_at=NOW(3) WHERE id=?", orderId);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        for (int i = 0; i < 20; i++) pool.submit(() -> settlement.settle(orderId));
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        for (int i = 0; i < 20; i++) settlement.settle(orderId);
        assertEquals("SETTLED", orders.get(orderId).status().name());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM t_idempotent_op WHERE op_key=?", Long.class, "SETTLE:ORDER:" + orderId));
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM t_ledger_entry WHERE biz_type='SETTLE' AND biz_id=?", Long.class, orderId));
    }
}

