package com.yu.errand.job;

import com.yu.errand.redis.DelayQueue;
import com.yu.errand.service.OrderService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class DelayQueuePoller {
    private static final Logger log = LoggerFactory.getLogger(DelayQueuePoller.class);
    private final DelayQueue queue;
    private final OrderService orders;

    public DelayQueuePoller(DelayQueue queue, OrderService orders) {
        this.queue = queue;
        this.orders = orders;
    }

    @Scheduled(fixedDelay = 500)
    public void poll() {
        double now = System.currentTimeMillis();
        queue.due(DelayQueue.CLAIM_KEY, now, 100).forEach(member -> {
            if (member.startsWith("CLAIM:")) {
                try { orders.timeoutClaim(parse(member)); queue.remove(DelayQueue.CLAIM_KEY, member); }
                catch (RuntimeException ex) { log.warn("delay claim event failed member={}", member, ex); }
            }
        });
        queue.due(DelayQueue.DELIVER_KEY, now, 100).forEach(member -> {
            if (member.startsWith("DELIVER:")) {
                try { orders.timeoutDeliver(parse(member)); queue.remove(DelayQueue.DELIVER_KEY, member); }
                catch (RuntimeException ex) { log.warn("delay deliver event failed member={}", member, ex); }
            }
        });
    }

    private long parse(String member) { return Long.parseLong(member.substring(member.indexOf(':') + 1)); }
}
