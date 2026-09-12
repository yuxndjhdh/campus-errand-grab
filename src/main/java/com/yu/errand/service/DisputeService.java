package com.yu.errand.service;

import com.yu.errand.common.BizException;
import com.yu.errand.common.ErrorCode;
import com.yu.errand.controller.dto.CompensationRequest;
import com.yu.errand.domain.AccountType;
import com.yu.errand.domain.model.Dispute;
import com.yu.errand.domain.model.Payment;
import com.yu.errand.repository.AccountRepository;
import com.yu.errand.repository.CompensationRepository;
import com.yu.errand.repository.DisputeRepository;
import com.yu.errand.repository.PaymentRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DisputeService {
    private final PaymentRepository payments;
    private final DisputeRepository disputes;
    private final CompensationRepository compensations;
    private final AccountRepository accounts;
    private final com.yu.errand.repository.LedgerRepository ledger;
    private final NotificationService notifications;

    public DisputeService(PaymentRepository payments, DisputeRepository disputes, CompensationRepository compensations,
                          AccountRepository accounts, com.yu.errand.repository.LedgerRepository ledger,
                          NotificationService notifications) {
        this.payments = payments;
        this.disputes = disputes;
        this.compensations = compensations;
        this.accounts = accounts;
        this.ledger = ledger;
        this.notifications = notifications;
    }

    @Transactional
    public Dispute open(long paymentId, long userId, String reason) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "payment not found"));
        if (payment.userId() != userId) throw new BizException(ErrorCode.FORBIDDEN, "payment belongs to another user");
        return disputes.insert(paymentId, userId, reason.trim());
    }

    @Transactional
    public Dispute compensate(long disputeId, long adminId, CompensationRequest request) {
        Dispute dispute = disputes.find(disputeId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "dispute not found"));
        if (!"OPEN".equals(dispute.status())) return dispute;
        if (compensations.exists(request.requestKey())) return disputes.find(disputeId).orElseThrow();
        try {
            compensations.insert(disputeId, dispute.userId(), request.requestKey(), request.amountCents(), request.reason(), adminId);
        } catch (DuplicateKeyException duplicate) {
            return disputes.find(disputeId).orElseThrow(() -> duplicate);
        }
        accounts.ensureAccounts(dispute.userId());
        accounts.ensureAccounts(1);
        accounts.lockUsers(Math.min(1, dispute.userId()), Math.max(1, dispute.userId()));
        accounts.change(1, AccountType.AVAILABLE, -request.amountCents());
        accounts.change(dispute.userId(), AccountType.AVAILABLE, request.amountCents());
        ledger.insert("COMPENSATION", disputeId, 1, AccountType.AVAILABLE, -request.amountCents());
        ledger.insert("COMPENSATION", disputeId, dispute.userId(), AccountType.AVAILABLE, request.amountCents());
        if (disputes.resolve(disputeId, adminId, request.reason()) != 1) {
            throw new BizException(ErrorCode.ILLEGAL_TRANSITION, "dispute was already resolved");
        }
        notifications.enqueue(dispute.userId(), "DISPUTE:" + disputeId, "DISPUTE", "dispute=" + disputeId);
        return disputes.find(disputeId).orElseThrow();
    }
}
