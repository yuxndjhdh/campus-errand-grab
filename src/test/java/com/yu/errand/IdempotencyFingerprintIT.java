package com.yu.errand;

import com.yu.errand.common.BizException;
import com.yu.errand.service.UserService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotencyFingerprintIT extends IntegrationTestBase {
    @Autowired private UserService users;

    @Test
    void sameKeyDifferentAmountIsRejected() {
        long userId = user("fingerprint-user");
        users.recharge(userId, 1000, "same-key");
        BizException failure = assertThrows(BizException.class, () -> users.recharge(userId, 2000, "same-key"));
        assertEquals("IDEMPOTENCY_CONFLICT", failure.errorCode().code());
        assertEquals(1000L, jdbc.queryForObject("SELECT balance_cents FROM t_account WHERE user_id=? AND account_type='AVAILABLE'", Long.class, userId));
    }

    @Test
    void sameKeyDifferentUserIsRejected() {
        long first = user("fingerprint-first");
        long second = user("fingerprint-second");
        users.recharge(first, 1000, "shared-key");
        BizException failure = assertThrows(BizException.class, () -> users.recharge(second, 1000, "shared-key"));
        assertEquals("IDEMPOTENCY_CONFLICT", failure.errorCode().code());
    }
}
