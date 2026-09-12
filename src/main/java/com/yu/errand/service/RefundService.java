package com.yu.errand.service;

import com.yu.errand.common.BizException;
import com.yu.errand.common.ErrorCode;
import com.yu.errand.controller.dto.RefundView;
import com.yu.errand.domain.AccountType;
import com.yu.errand.domain.model.Payment;
import com.yu.errand.domain.model.Refund;
import com.yu.errand.repository.AccountRepository;
import com.yu.errand.repository.LedgerRepository;
import com.yu.errand.repository.PaymentRepository;
import com.yu.errand.repository.RefundRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundService {
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final NotificationService notifications;

    public RefundService(PaymentRepository payments, RefundRepository refunds, AccountRepository accounts,
                         LedgerRepository ledger, NotificationService notifications) {
        this.payments = payments;
        this.refunds = refunds;
        this.accounts = accounts;
        this.ledger = ledger;
        this.notifications = notifications;
    }

    @Transactional
    public RefundView refund(long paymentId, long userId, long amountCents, String requestKey) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "payment not found"));
        if (payment.userId() != userId) throw new BizException(ErrorCode.FORBIDDEN, "payment belongs to another user");
        if (amountCents != payment.amountCents()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "sandbox refund must refund the full payment amount");
        }
        Refund replay = refunds.findByRequestKey(requestKey).orElse(null);
        if (replay != null) {
            if (replay.paymentId() != paymentId || replay.amountCents() != amountCents) {
                throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT, "refund request key was reused with different parameters");
            }
            return view(replay);
        }
        if (!"SETTLED".equals(payment.status())) {
            throw new BizException(ErrorCode.ILLEGAL_TRANSITION, "payment is not refundable in its current state");
        }
        Refund created;
        try {
            created = refunds.insert(paymentId, requestKey, userId, amountCents);
        } catch (DuplicateKeyException duplicate) {
            Refund requestReplay = refunds.findByRequestKey(requestKey).orElse(null);
            if (requestReplay != null) {
                if (requestReplay.paymentId() != paymentId || requestReplay.amountCents() != amountCents) {
                    throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT, "refund request key was reused with different parameters");
                }
                return view(requestReplay);
            }
            if (refunds.findByPaymentId(paymentId).isPresent()) {
                throw new BizException(ErrorCode.ILLEGAL_TRANSITION, "payment already has a refund");
            }
            throw duplicate;
        }
        accounts.ensureAccounts(userId);
        accounts.ensureAccounts(1);
        accounts.lockUsers(Math.min(1, userId), Math.max(1, userId));
        if (accounts.debitAvailable(userId, amountCents) != 1) {
            throw new BizException(ErrorCode.INSUFFICIENT_BALANCE, "available balance is insufficient for refund");
        }
        accounts.change(1, AccountType.AVAILABLE, amountCents);
        ledger.insert("REFUND", created.id(), userId, AccountType.AVAILABLE, -amountCents);
        ledger.insert("REFUND", created.id(), 1, AccountType.AVAILABLE, amountCents);
        payments.markRefunded(paymentId);
        notifications.enqueue(userId, "REFUND:" + created.id(), "REFUND", "refund=" + created.id());
        return view(created);
    }

    private RefundView view(Refund refund) {
        return new RefundView(refund.id(), refund.paymentId(), refund.requestKey(), refund.userId(), refund.amountCents(), refund.status());
    }
}
