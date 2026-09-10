package com.yu.errand;

import com.yu.errand.common.BizException;
import com.yu.errand.service.GrabService;
import com.yu.errand.service.OrderService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrabPrefilterSemanticsIT extends IntegrationTestBase {
    @Autowired private OrderService orders;
    @Autowired private GrabService grab;

    @Test
    void selfGrabDoesNotConsumeMarkerAndAnotherUserCanWin() {
        long publisher = user("prefilter-publisher");
        recharge(publisher, 10_000, "prefilter-recharge");
        long other = user("prefilter-other");
        long orderId = orders.create(publisher, "self guard", null, 1000, 120L).id();
        BizException failure = assertThrows(BizException.class, () -> grab.grab(orderId, publisher));
        assertEquals("SELF_GRAB", failure.errorCode().code());
        assertTrue(Boolean.TRUE.equals(redis.hasKey("grab:stock:" + orderId)));
        assertTrue(grab.grab(orderId, other).won());
    }

    @Test
    void consumedMarkerReturnsConflictWithoutChangingTheWinner() {
        long publisher = user("prefilter-winner");
        recharge(publisher, 10_000, "prefilter-winner-recharge");
        long first = user("prefilter-first");
        long second = user("prefilter-second");
        long orderId = orders.create(publisher, "marker conflict", null, 1000, 120L).id();
        assertTrue(grab.grab(orderId, first).won());
        BizException failure = assertThrows(BizException.class, () -> grab.grab(orderId, second));
        assertEquals("GRAB_REJECTED", failure.errorCode().code());
        assertEquals(first, orders.get(orderId).takerId());
    }
}
