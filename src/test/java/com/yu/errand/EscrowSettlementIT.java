package com.yu.errand;

import com.yu.errand.domain.AccountType;
import com.yu.errand.domain.model.ErrandOrder;
import com.yu.errand.service.GrabService;
import com.yu.errand.service.OrderService;
import com.yu.errand.service.SettlementService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EscrowSettlementIT extends IntegrationTestBase {
    @Autowired private OrderService orders;
    @Autowired private GrabService grab;
    @Autowired private SettlementService settlement;

    @Test
    void settlementSplitsEscrowAndConservesLedger() {
        long publisher = user("settlement-publisher");
        recharge(publisher, 10_000, "settlement-recharge");
        long taker = user("settlement-taker");
        long orderId = orders.create(publisher, "settle", null, 560, 120L).id();
        grab.grab(orderId, taker);
        orders.deliver(orderId, taker, settlement);

        assertEquals("SETTLED", orders.get(orderId).status().name());
        assertEquals(9_440, users.wallet(publisher).accounts().stream().filter(a -> a.accountType() == AccountType.AVAILABLE).findFirst().orElseThrow().balanceCents());
        assertEquals(504, users.wallet(taker).accounts().stream().filter(a -> a.accountType() == AccountType.AVAILABLE).findFirst().orElseThrow().balanceCents());
        assertEquals(56, users.wallet(2).accounts().stream().filter(a -> a.accountType() == AccountType.AVAILABLE).findFirst().orElseThrow().balanceCents());
        Long total = jdbc.queryForObject("SELECT COALESCE(SUM(amount_cents),0) FROM t_ledger_entry", Long.class);
        assertEquals(0L, total);
    }
}

