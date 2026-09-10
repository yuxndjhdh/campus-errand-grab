package com.yu.errand.service;

import com.yu.errand.common.BizException;
import com.yu.errand.common.ErrorCode;
import com.yu.errand.config.GrabProperties;
import com.yu.errand.domain.AccountType;
import com.yu.errand.domain.LedgerBizType;
import com.yu.errand.domain.OrderStatus;
import com.yu.errand.domain.model.ErrandOrder;
import com.yu.errand.domain.model.User;
import com.yu.errand.repository.AccountRepository;
import com.yu.errand.repository.IdempotencyRepository;
import com.yu.errand.repository.LedgerRepository;
import com.yu.errand.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;

@Service
public class SettlementService {
    private final OrderRepository orders;
    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final IdempotencyRepository idempotency;
    private final GrabProperties properties;
    private final OutboxEventService outbox;
    private final UserService users;

    public SettlementService(OrderRepository orders, AccountRepository accounts, LedgerRepository ledger,
                             IdempotencyRepository idempotency, GrabProperties properties, OutboxEventService outbox,
                             UserService users) {
        this.orders = orders;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.properties = properties;
        this.outbox = outbox;
        this.users = users;
    }

    @Transactional
    public boolean settle(long orderId) {
        ErrandOrder order = orders.findForUpdate(orderId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "order not found"));
        if (order.status() == OrderStatus.SETTLED) return false;
        if (order.status() != OrderStatus.DELIVERED || order.takerId() == null) {
            throw new BizException(ErrorCode.ILLEGAL_TRANSITION, "order is not ready for settlement");
        }
        users.requireBusinessUser(order.publisherId());
        users.requireBusinessUser(order.takerId());
        String opKey = "SETTLE:ORDER:" + orderId;
        if (idempotency.start(opKey, LedgerBizType.SETTLE.name(), orderId,
                RequestHasher.sha256("SETTLE|" + orderId)) != com.yu.errand.repository.IdempotencyState.NEW) return false;

        long commission = CommissionMath.commission(order.rewardCents(), properties.getCommission().getRate());
        long takerAmount = order.rewardCents() - commission;
        long platformId = properties.getCommission().getPlatformUserId();
        users.requireRole(platformId, "PLATFORM");
        accounts.ensureAccounts(platformId);
        accounts.lockUsers(Arrays.stream(new long[]{order.publisherId(), order.takerId(), platformId}).distinct().sorted().toArray());
        if (accounts.debitFrozen(order.publisherId(), order.rewardCents()) != 1) {
            throw new IllegalStateException("frozen escrow is lower than reward");
        }
        accounts.change(order.takerId(), AccountType.AVAILABLE, takerAmount);
        accounts.change(platformId, AccountType.AVAILABLE, commission);
        if (orders.markSettled(orderId, commission) != 1) {
            throw new BizException(ErrorCode.ILLEGAL_TRANSITION, "settlement lost race");
        }
        insertEntries(order, platformId, takerAmount, commission);
        outbox.enqueue("ORDER_TERMINAL", orderId);
        return true;
    }

    private void insertEntries(ErrandOrder order, long platformId, long takerAmount, long commission) {
        java.util.Map<LedgerKey, Long> entries = new java.util.TreeMap<>();
        add(entries, order.publisherId(), AccountType.FROZEN, -order.rewardCents());
        add(entries, order.takerId(), AccountType.AVAILABLE, takerAmount);
        add(entries, platformId, AccountType.AVAILABLE, commission);
        entries.forEach((key, amount) -> ledger.insert(LedgerBizType.SETTLE.name(), order.id(), key.userId(), key.accountType(), amount));
    }

    private static void add(java.util.Map<LedgerKey, Long> entries, long userId, AccountType type, long amount) {
        entries.merge(new LedgerKey(userId, type), amount, Long::sum);
    }

    private record LedgerKey(long userId, AccountType accountType) implements Comparable<LedgerKey> {
        @Override public int compareTo(LedgerKey other) {
            int user = Long.compare(userId, other.userId);
            return user != 0 ? user : accountType.compareTo(other.accountType);
        }
    }
}
