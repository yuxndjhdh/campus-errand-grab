package com.yu.errand;

import com.yu.errand.service.GrabService;
import com.yu.errand.service.OrderService;
import com.yu.errand.service.ReconService;
import com.yu.errand.service.SettlementService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FundConservationIT extends IntegrationTestBase {
    @Autowired private OrderService orders;
    @Autowired private GrabService grab;
    @Autowired private SettlementService settlement;
    @Autowired private ReconService recon;

    @Test
    void repeatedSettlementAndCancellationPreserveAllInvariants() {
        long publisher = user("fund-publisher");
        recharge(publisher, 100_000, "fund-recharge");
        long taker = user("fund-taker");
        for (int i = 0; i < 20; i++) {
            long orderId = orders.create(publisher, "fund-" + i, null, 1000 + i, 120L).id();
            if (i % 2 == 0) {
                grab.grab(orderId, taker);
                orders.deliver(orderId, taker, settlement);
            } else {
                orders.cancel(orderId, publisher);
            }
        }
        assertTrue(recon.run().passed());
    }
}

