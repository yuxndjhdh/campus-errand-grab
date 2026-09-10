package com.yu.errand;

import com.yu.errand.common.BizException;
import com.yu.errand.service.GrabService;
import com.yu.errand.service.OrderService;
import com.yu.errand.service.UserService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SystemAccountIsolationIT extends IntegrationTestBase {
    @Autowired private OrderService orders;
    @Autowired private GrabService grab;
    @Autowired private UserService users;

    @Test
    void systemAccountsCannotCreateOrGrabOrders() {
        BizException createFailure = assertThrows(BizException.class,
                () -> orders.create(1, "mint order", null, 100, 120L));
        assertEquals("SYSTEM_ACCOUNT_FORBIDDEN", createFailure.errorCode().code());
        BizException grabFailure = assertThrows(BizException.class, () -> grab.grab(1, 2));
        assertEquals("SYSTEM_ACCOUNT_FORBIDDEN", grabFailure.errorCode().code());
    }
}
