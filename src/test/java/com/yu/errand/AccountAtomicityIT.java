package com.yu.errand;

import com.yu.errand.common.BizException;
import com.yu.errand.service.OrderService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountAtomicityIT extends IntegrationTestBase {
    @Autowired private OrderService orders;

    @Test
    void insufficientBalanceRollsBackOrderAndLedger() {
        long publisher = user("atomicity-insufficient");
        assertThrows(BizException.class, () -> orders.create(publisher, "too expensive", null, 1000, 120L));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM t_errand_order WHERE publisher_id=?", Long.class, publisher));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM t_ledger_entry WHERE user_id=?", Long.class, publisher));
    }

    @Test
    void cancellationReturnsEscrowExactlyOnce() {
        long publisher = user("atomicity-cancel");
        recharge(publisher, 10_000, "atomicity-cancel-recharge");
        long orderId = orders.create(publisher, "cancel once", null, 1000, 120L).id();
        orders.cancel(orderId, publisher);
        assertEquals(10_000L, jdbc.queryForObject("SELECT balance_cents FROM t_account WHERE user_id=? AND account_type='AVAILABLE'", Long.class, publisher));
        assertEquals(0L, jdbc.queryForObject("SELECT balance_cents FROM t_account WHERE user_id=? AND account_type='FROZEN'", Long.class, publisher));
    }
}
