package com.yu.errand;

import com.yu.errand.common.BizException;
import com.yu.errand.service.GrabService;
import com.yu.errand.service.OrderService;
import com.yu.errand.service.SettlementService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertThrows;

class StateMachineIT extends IntegrationTestBase {
    @Autowired private OrderService orders;
    @Autowired private GrabService grab;
    @Autowired private SettlementService settlement;

    @Test
    void illegalTransitionsAreRejected() {
        long publisher = user("state-publisher");
        recharge(publisher, 20_000, "state-recharge");
        long taker = user("state-taker");
        long orderId = orders.create(publisher, "state", null, 1000, 120L).id();

        assertThrows(BizException.class, () -> orders.deliver(orderId, taker, settlement));
        assertThrows(BizException.class, () -> orders.cancel(orderId, taker));
        orders.cancel(orderId, publisher);
        assertThrows(BizException.class, () -> grab.grab(orderId, taker));
    }

    @Test
    void settledOrderCannotBeGrabbedOrCancelled() {
        long publisher = user("terminal-publisher");
        recharge(publisher, 20_000, "terminal-recharge");
        long taker = user("terminal-taker");
        long orderId = orders.create(publisher, "terminal", null, 1000, 120L).id();
        grab.grab(orderId, taker);
        orders.deliver(orderId, taker, settlement);
        assertThrows(BizException.class, () -> grab.grab(orderId, publisher));
        assertThrows(BizException.class, () -> orders.cancel(orderId, publisher));
    }
}

