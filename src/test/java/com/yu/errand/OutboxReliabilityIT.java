package com.yu.errand;

import com.yu.errand.common.BizException;
import com.yu.errand.domain.model.OutboxEvent;
import com.yu.errand.job.OutboxClaimService;
import com.yu.errand.job.OutboxWorker;
import com.yu.errand.redis.DelayQueue;
import com.yu.errand.redis.GrabMarker;
import com.yu.errand.service.GrabService;
import com.yu.errand.service.OrderService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class OutboxReliabilityIT extends IntegrationTestBase {
    @Autowired private OrderService orders;
    @Autowired private GrabService grab;
    @Autowired private OutboxClaimService claims;
    @Autowired private OutboxWorker worker;
    @Autowired private GrabMarker marker;
    @Autowired private DelayQueue delayQueue;
    @Autowired private MockMvc mockMvc;
    @Autowired private com.yu.errand.security.JwtService jwt;

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
        long eventId = outboxId("ORDER_PUBLISHED", orderId);
        jdbc.update("UPDATE t_outbox_event SET status='DEAD',retry_count=12,last_error='test failure' WHERE id=?", eventId);
        assertEquals(1, outboxReplay(eventId));
        worker.publish();
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM t_outbox_event WHERE id=?", String.class, eventId));
    }

    @Test
    void businessRollbackRollsBackItsOutboxEvent() {
        long publisher = user("outbox-rollback-publisher");
        assertThrows(BizException.class, () -> orders.create(publisher, "insufficient", null, 1000, 120L));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM t_errand_order", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox_event", Long.class));
    }

    @Test
    void twoWorkersCannotClaimTheSameEvent() throws Exception {
        long publisher = user("outbox-claim-publisher");
        recharge(publisher, 10_000, "outbox-claim-recharge");
        orders.create(publisher, "outbox claim", null, 1000, 120L);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<OutboxEvent>> first = submitClaim(pool, ready, start);
            Future<List<OutboxEvent>> second = submitClaim(pool, ready, start);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<OutboxEvent> firstEvents = first.get(5, TimeUnit.SECONDS);
            List<OutboxEvent> secondEvents = second.get(5, TimeUnit.SECONDS);
            Set<Long> ids = new HashSet<>();
            firstEvents.forEach(event -> assertTrue(ids.add(event.id())));
            secondEvents.forEach(event -> assertTrue(ids.add(event.id())));
            assertEquals(1, ids.size());
            assertEquals(1, firstEvents.size() + secondEvents.size());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void expiredProcessingLeaseCanBeClaimedAgain() {
        long publisher = user("outbox-lease-publisher");
        recharge(publisher, 10_000, "outbox-lease-recharge");
        long orderId = orders.create(publisher, "outbox lease", null, 1000, 120L).id();
        long eventId = outboxId("ORDER_PUBLISHED", orderId);
        LocalDateTime now = LocalDateTime.now();
        assertEquals(1, claims.claimBatch(1, now, now.plusSeconds(30)).size());
        jdbc.update("UPDATE t_outbox_event SET locked_until=DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id=?", eventId);

        worker.publish();

        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM t_outbox_event WHERE id=?", String.class, eventId));
    }

    @Test
    void duplicateConsumptionLeavesRedisDerivedStateIdempotent() {
        long publisher = user("outbox-idempotent-publisher");
        recharge(publisher, 10_000, "outbox-idempotent-recharge");
        long orderId = orders.create(publisher, "outbox idempotent", null, 1000, 120L).id();
        long eventId = outboxId("ORDER_PUBLISHED", orderId);
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();

        worker.publish();
        jdbc.update("UPDATE t_outbox_event SET status='PENDING',next_retry_at=NOW(3),locked_until=NULL WHERE id=?", eventId);
        worker.publish();

        assertEquals(1L, redis.opsForZSet().count(DelayQueue.CLAIM_KEY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY));
        assertTrue(Boolean.TRUE.equals(redis.hasKey("grab:stock:" + orderId)));
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM t_outbox_event WHERE id=?", String.class, eventId));
    }

    @Test
    void redisSuccessBeforeCrashIsRecoveredAfterLeaseExpiry() {
        long publisher = user("outbox-crash-publisher");
        recharge(publisher, 10_000, "outbox-crash-recharge");
        long orderId = orders.create(publisher, "outbox crash", null, 1000, 120L).id();
        long eventId = outboxId("ORDER_PUBLISHED", orderId);
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        LocalDateTime now = LocalDateTime.now();
        OutboxEvent claimed = claims.claimBatch(1, now, now.plusSeconds(30)).get(0);
        marker.armStrict(orderId, orders.get(orderId).claimDeadlineAt(), publisher);
        delayQueue.addClaimStrict(orderId, orders.get(orderId).claimDeadlineAt());
        jdbc.update("UPDATE t_outbox_event SET locked_until=DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id=?", eventId);

        worker.publish();

        assertEquals("PROCESSING", claimed.status());
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM t_outbox_event WHERE id=?", String.class, eventId));
        assertTrue(Boolean.TRUE.equals(redis.hasKey("grab:stock:" + orderId)));
    }

    @Test
    void poisonEventDoesNotBlockTheRestOfTheBatch() {
        outboxInsert("POISON", 999_999L);
        long publisher = user("outbox-poison-publisher");
        recharge(publisher, 10_000, "outbox-poison-recharge");
        long orderId = orders.create(publisher, "healthy after poison", null, 1000, 120L).id();

        worker.publish();

        assertEquals("PUBLISHED", jdbc.queryForObject(
                "SELECT status FROM t_outbox_event WHERE event_type='ORDER_PUBLISHED' AND biz_id=?", String.class, orderId));
        assertEquals("RETRY", jdbc.queryForObject(
                "SELECT status FROM t_outbox_event WHERE event_type='POISON' AND biz_id=999999", String.class));
    }

    @Test
    void repeatedFailuresUseBackoffAndMoveToDead() {
        outboxInsert("POISON_RETRY", 888_888L);
        long eventId = jdbc.queryForObject("SELECT id FROM t_outbox_event WHERE event_type='POISON_RETRY'", Long.class);

        for (int attempt = 1; attempt <= 12; attempt++) {
            jdbc.update("UPDATE t_outbox_event SET next_retry_at=NOW(3) WHERE id=?", eventId);
            worker.publish();
            if (attempt < 12) {
                assertEquals("RETRY", jdbc.queryForObject("SELECT status FROM t_outbox_event WHERE id=?", String.class, eventId));
                assertEquals(attempt, jdbc.queryForObject("SELECT retry_count FROM t_outbox_event WHERE id=?", Integer.class, eventId));
                long expectedDelayMillis = Math.min(300, 1L << Math.min(attempt - 1, 8)) * 1000L;
                long actualDelayMillis = jdbc.queryForObject(
                        "SELECT TIMESTAMPDIFF(MILLISECOND,NOW(3),next_retry_at) FROM t_outbox_event WHERE id=?",
                        Long.class, eventId);
                assertTrue(actualDelayMillis >= expectedDelayMillis - 500,
                        "retry backoff was shorter than expected for attempt " + attempt);
            }
        }
        assertEquals("DEAD", jdbc.queryForObject("SELECT status FROM t_outbox_event WHERE id=?", String.class, eventId));
        assertEquals(12, jdbc.queryForObject("SELECT retry_count FROM t_outbox_event WHERE id=?", Integer.class, eventId));
    }

    @Test
    void adminEndpointReplaysDeadEvent() throws Exception {
        long publisher = user("outbox-admin-replay-publisher");
        recharge(publisher, 10_000, "outbox-admin-replay-recharge");
        long orderId = orders.create(publisher, "admin replay", null, 1000, 120L).id();
        long eventId = outboxId("ORDER_PUBLISHED", orderId);
        jdbc.update("UPDATE t_outbox_event SET status='DEAD',retry_count=12,last_error='test failure' WHERE id=?", eventId);

        long adminId = users.register("outbox-admin", "Outbox Admin", "admin-password").id();
        jdbc.update("UPDATE t_user SET role='ADMIN' WHERE id=?", adminId);
        String token = jwt.issue(users.requireUser(adminId));
        mockMvc.perform(post("/api/admin/outbox/{id}/replay", eventId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        worker.publish();

        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM t_outbox_event WHERE id=?", String.class, eventId));
    }

    private Future<List<OutboxEvent>> submitClaim(ExecutorService pool, CountDownLatch ready, CountDownLatch start) {
        return pool.submit(() -> {
            ready.countDown();
            start.await();
            LocalDateTime now = LocalDateTime.now();
            return claims.claimBatch(50, now, now.plusSeconds(30));
        });
    }

    private long outboxId(String eventType, long bizId) {
        return jdbc.queryForObject("SELECT id FROM t_outbox_event WHERE event_type=? AND biz_id=?", Long.class, eventType, bizId);
    }

    private int outboxReplay(long eventId) {
        return jdbc.update("UPDATE t_outbox_event SET status='PENDING',retry_count=0,next_retry_at=NOW(3),locked_until=NULL,last_error=NULL WHERE id=? AND status='DEAD'", eventId);
    }

    private void outboxInsert(String eventType, long bizId) {
        jdbc.update("INSERT INTO t_outbox_event(event_type,biz_id,payload_json) VALUES (?, ?, '{}')", eventType, bizId);
    }
}
