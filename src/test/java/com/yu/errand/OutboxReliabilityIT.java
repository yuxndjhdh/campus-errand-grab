package com.yu.errand;

import com.yu.errand.service.GrabService;
import com.yu.errand.service.OrderService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutboxReliabilityIT extends IntegrationTestBase {
    @Autowired private OrderService orders;
    @Autowired private GrabService grab;

    @Test
    void publishingAnOrderWritesPublishedEventInSameDatabaseTransaction() {
        long publisher = user("outbox-publisher");
        recharge(publisher, 10_000, "outbox-recharge");
        long orderId = orders.create(publisher, "outbox order", null, 1000, 120L).id();
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox_event WHERE event_type='ORDER_PUBLISHED' AND biz_id=?", Long.class, orderId));
    }

    @Test
    void takingAnOrderWritesDeliveryEvent() {
        long publisher = user("outbox-taken-publisher");
        recharge(publisher, 10_000, "outbox-taken-recharge");
        long taker = user("outbox-taken-taker");
        long orderId = orders.create(publisher, "outbox taken", null, 1000, 120L).id();
        grab.grab(orderId, taker);
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox_event WHERE event_type='ORDER_TAKEN' AND biz_id=?", Long.class, orderId));
    }

    @Test
    void terminalEventCanBeReplayedAfterAWorkerFailure() {
        long publisher = user("outbox-replay-publisher");
        recharge(publisher, 10_000, "outbox-replay-recharge");
        long orderId = orders.create(publisher, "outbox replay", null, 1000, 120L).id();
        jdbc.update("UPDATE t_outbox_event SET status='DEAD',retry_count=12,last_error='test failure' WHERE event_type='ORDER_PUBLISHED' AND biz_id=?", orderId);
        assertEquals(1, jdbc.update("UPDATE t_outbox_event SET status='PENDING',retry_count=0,last_error=NULL WHERE event_type='ORDER_PUBLISHED' AND biz_id=?", orderId));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM t_outbox_event WHERE event_type='ORDER_PUBLISHED' AND biz_id=?", String.class, orderId));
    }
}
