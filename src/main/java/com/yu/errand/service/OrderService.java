package com.yu.errand.service;

import com.yu.errand.common.BizException;
import com.yu.errand.common.ErrorCode;
import com.yu.errand.config.GrabProperties;
import com.yu.errand.domain.AccountType;
import com.yu.errand.domain.LedgerBizType;
import com.yu.errand.domain.OrderStatus;
import com.yu.errand.domain.model.ErrandOrder;
import com.yu.errand.redis.DelayQueue;
import com.yu.errand.redis.GrabMarker;
import com.yu.errand.repository.AccountRepository;
import com.yu.errand.repository.LedgerRepository;
import com.yu.errand.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OrderService {
    private final UserService userService;
    private final OrderRepository orders;
    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final GrabProperties properties;
    private final GrabMarker marker;
    private final DelayQueue delayQueue;
    private final OutboxEventService outbox;

    public OrderService(UserService userService, OrderRepository orders, AccountRepository accounts,
                        LedgerRepository ledger, GrabProperties properties, GrabMarker marker,
                        DelayQueue delayQueue, OutboxEventService outbox) {
        this.userService = userService;
        this.orders = orders;
        this.accounts = accounts;
        this.ledger = ledger;
        this.properties = properties;
        this.marker = marker;
        this.delayQueue = delayQueue;
        this.outbox = outbox;
    }

    @Transactional
    public ErrandOrder create(long publisherId, String title, String detail, long rewardCents, Long requestedTtl) {
        userService.requireBusinessUser(publisherId);
        if (rewardCents <= 0) throw new BizException(ErrorCode.VALIDATION_FAILED, "rewardCents must be positive");
        long ttl = requestedTtl == null ? properties.getOrder().getClaimTtlSeconds() : requestedTtl;
        if (ttl <= 0 || ttl > 86400) throw new BizException(ErrorCode.VALIDATION_FAILED, "claimTtlSeconds out of range");
        accounts.ensureAccounts(publisherId);
        long id = orders.insert(publisherId, title.trim(), detail, rewardCents, ttl);
        if (accounts.debitAvailable(publisherId, rewardCents) != 1) {
            throw new BizException(ErrorCode.INSUFFICIENT_BALANCE, "available balance is insufficient");
        }
        accounts.change(publisherId, AccountType.FROZEN, rewardCents);
        ledger.insert(LedgerBizType.FREEZE.name(), id, publisherId, AccountType.AVAILABLE, -rewardCents);
        ledger.insert(LedgerBizType.FREEZE.name(), id, publisherId, AccountType.FROZEN, rewardCents);
        ErrandOrder created = get(id);
        marker.arm(id, created.claimDeadlineAt(), created.publisherId());
        delayQueue.addClaim(id, created.claimDeadlineAt());
        outbox.enqueue("ORDER_PUBLISHED", id);
        return created;
    }

    @Transactional(readOnly = true)
    public ErrandOrder get(long id) {
        return orders.find(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "order not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<ErrandOrder> list(int limit, int offset) {
        return orders.list(Math.min(Math.max(limit, 1), 100), Math.max(offset, 0));
    }

    @Transactional
    public ErrandOrder cancel(long orderId, long publisherId) {
        ErrandOrder before = orders.findForUpdate(orderId).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "order not found"));
        if (before.publisherId() != publisherId) throw new BizException(ErrorCode.ILLEGAL_TRANSITION, "only publisher can cancel");
        if (before.status() != OrderStatus.PUBLISHED && before.status() != OrderStatus.TAKEN) {
            throw new BizException(ErrorCode.ILLEGAL_TRANSITION, "order is terminal");
        }
        if (orders.cancelByPublisher(orderId, publisherId) != 1) throw new BizException(ErrorCode.ILLEGAL_TRANSITION, "cancel lost race");
        refund(before, LedgerBizType.CANCEL);
        outbox.enqueue("ORDER_TERMINAL", orderId);
        return get(orderId);
    }

    public ErrandOrder deliver(long orderId, long takerId, SettlementService settlement) {
        if (orders.markDelivered(orderId, takerId) != 1) {
            ErrandOrder current = orders.find(orderId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "order not found"));
            throw deliveryFailure(current, takerId);
        }
        try {
            settlement.settle(orderId);
        } catch (RuntimeException ex) {
            // DELIVERED remains durable; SettleRetryJob retries the idempotent operation.
            orders.recordSettlementFailure(orderId, 1, LocalDateTime.now().plusSeconds(1), ex.toString());
        }
        return get(orderId);
    }

    private BizException deliveryFailure(ErrandOrder current, long takerId) {
        if (current.deliverDeadlineAt() != null && !current.deliverDeadlineAt().isAfter(LocalDateTime.now())) {
            return new BizException(ErrorCode.EXPIRED, "delivery deadline has passed");
        }
        if (current.status() != OrderStatus.TAKEN) {
            return new BizException(ErrorCode.ILLEGAL_TRANSITION, "order is not waiting for delivery");
        }
        if (current.takerId() == null || current.takerId() != takerId) {
            return new BizException(ErrorCode.ILLEGAL_TRANSITION, "only the taker can deliver a TAKEN order");
        }
        return new BizException(ErrorCode.ILLEGAL_TRANSITION, "delivery transition was rejected");
    }

    @Transactional
    public boolean timeoutClaim(long orderId) {
        ErrandOrder before = orders.findForUpdate(orderId).orElse(null);
        if (before == null || before.status() != OrderStatus.PUBLISHED) return false;
        if (orders.timeoutClaim(orderId) != 1) return false;
        refund(before, LedgerBizType.CANCEL);
        outbox.enqueue("ORDER_TERMINAL", orderId);
        return true;
    }

    @Transactional
    public boolean timeoutDeliver(long orderId) {
        ErrandOrder before = orders.findForUpdate(orderId).orElse(null);
        if (before == null || before.status() != OrderStatus.TAKEN) return false;
        if (orders.timeoutDeliver(orderId) != 1) return false;
        refund(before, LedgerBizType.CANCEL);
        outbox.enqueue("ORDER_TERMINAL", orderId);
        return true;
    }

    private void refund(ErrandOrder order, LedgerBizType bizType) {
        if (accounts.debitFrozen(order.publisherId(), order.rewardCents()) != 1) {
            throw new IllegalStateException("frozen escrow is lower than order reward");
        }
        accounts.change(order.publisherId(), AccountType.AVAILABLE, order.rewardCents());
        ledger.insert(bizType.name(), order.id(), order.publisherId(), AccountType.FROZEN, -order.rewardCents());
        ledger.insert(bizType.name(), order.id(), order.publisherId(), AccountType.AVAILABLE, order.rewardCents());
    }
}
