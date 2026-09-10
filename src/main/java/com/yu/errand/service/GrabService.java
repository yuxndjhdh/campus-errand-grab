package com.yu.errand.service;

import com.yu.errand.common.BizException;
import com.yu.errand.common.ErrorCode;
import com.yu.errand.config.GrabProperties;
import com.yu.errand.controller.dto.GrabResult;
import com.yu.errand.domain.OrderStatus;
import com.yu.errand.domain.model.ErrandOrder;
import com.yu.errand.redis.DelayQueue;
import com.yu.errand.redis.GrabRateLimiter;
import com.yu.errand.redis.GrabMarker;
import com.yu.errand.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GrabService {
    private final UserService users;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final GrabMarker marker;
    private final GrabProperties properties;
    private final GrabStats stats;
    private final DelayQueue delayQueue;
    private final OutboxEventService outbox;
    private final GrabRateLimiter rateLimiter;

    public GrabService(UserService users, OrderRepository orders, OrderService orderService, GrabMarker marker,
                       GrabProperties properties, GrabStats stats, DelayQueue delayQueue, OutboxEventService outbox,
                       GrabRateLimiter rateLimiter) {
        this.users = users;
        this.orders = orders;
        this.orderService = orderService;
        this.marker = marker;
        this.properties = properties;
        this.stats = stats;
        this.delayQueue = delayQueue;
        this.outbox = outbox;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public GrabResult grab(long orderId, long takerId) {
        users.requireBusinessUser(takerId);
        stats.attempt();
        if (!rateLimiter.allow(takerId, orderId)) {
            stats.rateLimitRejected();
            return reject(ErrorCode.RATE_LIMITED, "grab rate limit exceeded");
        }
        GrabMarker.Decision decision = properties.getGrab().isRedisPrefilterEnabled()
                ? marker.tryPass(orderId, takerId, System.currentTimeMillis()) : GrabMarker.Decision.PASS;
        if (decision == GrabMarker.Decision.FILTERED) {
            stats.filter();
            return reject(ErrorCode.GRAB_REJECTED, "grab was rejected by the concurrency guard");
        }
        if (decision == GrabMarker.Decision.SELF_GRAB) {
            return reject(ErrorCode.SELF_GRAB, "publisher cannot grab own order");
        }
        if (decision == GrabMarker.Decision.EXPIRED) {
            return reject(ErrorCode.EXPIRED, "claim deadline has passed");
        }

        stats.dbCasAttempt();
        int changed = orders.claim(orderId, takerId, properties.getOrder().getDeliverTtlSeconds());
        if (changed == 1) {
            stats.winner();
            ErrandOrder won = current(orderId);
            delayQueue.addDeliver(orderId, won.deliverDeadlineAt());
            outbox.enqueue("ORDER_TAKEN", orderId);
            return new GrabResult(true, won, "WON");
        }

        ErrandOrder current = current(orderId);
        boolean expired = current.status() == OrderStatus.PUBLISHED && orders.claimExpired(orderId);
        if (current.status() == OrderStatus.PUBLISHED && current.publisherId() == takerId) {
            marker.rearm(orderId, current.claimDeadlineAt(), current.publisherId());
        } else if (current.status() == OrderStatus.PUBLISHED && !expired) {
            // A marker may have been consumed by this request while another DB transaction won or rolled back.
            marker.rearm(orderId, current.claimDeadlineAt(), current.publisherId());
        }
        return rejectFor(current, takerId);
    }

    private ErrandOrder current(long orderId) {
        return orders.find(orderId).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "order not found"));
    }

    private GrabResult rejectFor(ErrandOrder order, long takerId) {
        ErrorCode code;
        String message;
        if (order.status() == OrderStatus.PUBLISHED && order.publisherId() == takerId) {
            code = ErrorCode.SELF_GRAB;
            message = "publisher cannot grab own order";
        } else if (order.status() == OrderStatus.PUBLISHED && orders.claimExpired(order.id())) {
            code = ErrorCode.EXPIRED;
            message = "claim deadline has passed";
        } else if (order.status() == OrderStatus.TAKEN) {
            code = ErrorCode.ALREADY_TAKEN;
            message = "order already has a taker";
        } else if (order.status() == OrderStatus.PUBLISHED) {
            code = ErrorCode.GRAB_REJECTED;
            message = "grab was rejected by the concurrency guard";
        } else {
            code = ErrorCode.ILLEGAL_TRANSITION;
            message = "order is no longer grab-able";
        }
        return reject(code, message);
    }

    private GrabResult reject(ErrorCode code, String message) {
        stats.reject();
        throw new BizException(code, message);
    }
}
