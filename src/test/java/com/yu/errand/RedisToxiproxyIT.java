package com.yu.errand;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class RedisToxiproxyIT {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0")
            .dependsOn(REDIS);

    @Test
    void redisDisconnectIsObservableAndRecovers() {
        var proxy = TOXIPROXY.getProxy(REDIS, 6379);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                TOXIPROXY.getHost(), TOXIPROXY.getMappedPort(proxy.getProxyPort()));
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        try {
            template.opsForValue().set("toxiproxy-test", "ok");
            assertEquals("ok", template.opsForValue().get("toxiproxy-test"));
            proxy.setConnectionCut(true);
            assertThrows(DataAccessException.class, () -> template.opsForValue().get("toxiproxy-test"));
            proxy.setConnectionCut(false);
            assertEquals("ok", template.opsForValue().get("toxiproxy-test"));
        } finally {
            factory.destroy();
        }
    }
}
