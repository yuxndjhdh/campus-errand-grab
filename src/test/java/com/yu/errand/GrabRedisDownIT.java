package com.yu.errand;

import com.yu.errand.controller.dto.GrabResult;
import com.yu.errand.service.GrabService;
import com.yu.errand.service.OrderService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GrabRedisDownIT extends IntegrationTestBase {
    @Autowired private OrderService orders;
    @Autowired private GrabService grab;

    @Test
    void missingRedisMarkerDoesNotBreakDatabaseWinner() {
        long publisher = user("redis-down-publisher");
        recharge(publisher, 100_000, "redis-down-recharge");
        long taker = user("redis-down-taker");
        long orderId = orders.create(publisher, "redis is optional", null, 1000, 120L).id();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        GrabResult result = grab.grab(orderId, taker);
        assertTrue(result.won());
    }
}

