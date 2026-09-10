package com.yu.errand.job;

import com.yu.errand.redis.DelayQueue;
import com.yu.errand.monitoring.ReliabilityMetrics;
import com.yu.errand.service.OrderService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class DelayQueuePoller {
    private static final Logger log = LoggerFactory.getLogger(DelayQueuePoller.class);
    private final DelayQueue queue;
    private final OrderService orders;
    private final ReliabilityMetrics metrics;

    public DelayQueuePoller(DelayQueue queue, OrderService orders, ReliabilityMetrics metrics) {
        this.queue = queue;
        this.orders = orders;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 500)
    public void poll() {
        double now = System.currentTimeMillis();
        AtomicLong maxLagMillis = new AtomicLong();
        queue.due(DelayQueue.CLAIM_KEY, now, 100).forEach(member -> {
            observeLag(DelayQueue.CLAIM_KEY, member, now, maxLagMillis);
            if (member.startsWith("CLAIM:")) {
                try { orders.timeoutClaim(parse(member)); queue.remove(DelayQueue.CLAIM_KEY, member); }
                catch (RuntimeException ex) { log.warn("delay claim event failed member={}", member, ex); }
            }
        });
        queue.due(DelayQueue.DELIVER_KEY, now, 100).forEach(member -> {
            observeLag(DelayQueue.DELIVER_KEY, member, now, maxLagMillis);
            if (member.startsWith("DELIVER:")) {
                try { orders.timeoutDeliver(parse(member)); queue.remove(DelayQueue.DELIVER_KEY, member); }
                catch (RuntimeException ex) { log.warn("delay deliver event failed member={}", member, ex); }
            }
        });
        metrics.setTimeoutQueueLagMillis(maxLagMillis.get());
    }

    private void observeLag(String key, String member, double nowMillis, AtomicLong maxLagMillis) {
        Double score = queue.score(key, member);
        if (score == null) return;
        long lagMillis = Math.max(0L, (long) (nowMillis - score));
        maxLagMillis.accumulateAndGet(lagMillis, Math::max);
    }

    private long parse(String member) { return Long.parseLong(member.substring(member.indexOf(':') + 1)); }
}
