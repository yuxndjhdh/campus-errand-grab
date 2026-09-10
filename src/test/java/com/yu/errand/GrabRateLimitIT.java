package com.yu.errand;

import com.yu.errand.common.BizException;
import com.yu.errand.config.GrabProperties;
import com.yu.errand.service.GrabService;
import com.yu.errand.service.OrderService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GrabRateLimitIT extends IntegrationTestBase {
    @Autowired private OrderService orders;
    @Autowired private GrabService grab;
    @Autowired private GrabProperties properties;

    @Test
    void userRateLimitStopsLaterGrabBeforeDatabaseCas() {
        int oldLimit = properties.getGrab().getRateLimitPerUser();
        try {
            properties.getGrab().setRateLimitPerUser(1);
            long publisher = user("rate-publisher");
            recharge(publisher, 10_000, "rate-recharge");
            long taker = user("rate-taker");
            long firstOrder = orders.create(publisher, "rate one", null, 1000, 120L).id();
            long secondOrder = orders.create(publisher, "rate two", null, 1000, 120L).id();
            grab.grab(firstOrder, taker);
            BizException failure = assertThrows(BizException.class, () -> grab.grab(secondOrder, taker));
            assertEquals("RATE_LIMITED", failure.errorCode().code());
        } finally {
            properties.getGrab().setRateLimitPerUser(oldLimit);
        }
    }
}
