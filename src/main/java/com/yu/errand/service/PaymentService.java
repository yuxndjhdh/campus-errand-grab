package com.yu.errand.service;

import com.yu.errand.common.BizException;
import com.yu.errand.common.ErrorCode;
import com.yu.errand.controller.dto.PaymentCallbackRequest;
import com.yu.errand.controller.dto.PaymentView;
import com.yu.errand.domain.model.Payment;
import com.yu.errand.payment.PaymentProvider;
import com.yu.errand.repository.PaymentRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
    private final PaymentRepository payments;
    private final PaymentProvider provider;
    private final UserService users;
    private final NotificationService notifications;

    public PaymentService(PaymentRepository payments, PaymentProvider provider, UserService users,
                          NotificationService notifications) {
        this.payments = payments;
        this.provider = provider;
        this.users = users;
        this.notifications = notifications;
    }

    @Transactional
    public PaymentView callback(PaymentCallbackRequest request) {
        provider.verify(request);
        Payment existing = payments.findByEvent(provider.name(), request.eventId()).orElse(null);
        if (existing != null) return sameRequest(existing, request);
        Payment byProvider = payments.findByProviderId(provider.name(), request.providerPaymentId()).orElse(null);
        if (byProvider != null) return sameRequest(byProvider, request);
        users.requireBusinessUser(request.userId());
        Payment created;
        try {
            created = payments.insert(provider.name(), request.providerPaymentId(), request.eventId(),
                    request.userId(), request.amountCents());
        } catch (DuplicateKeyException duplicate) {
            Payment raced = payments.findByEvent(provider.name(), request.eventId())
                    .or(() -> payments.findByProviderId(provider.name(), request.providerPaymentId()))
                    .orElseThrow(() -> duplicate);
            return sameRequest(raced, request);
        }
        // Reserve the provider/event uniqueness before changing the internal ledger.
        // A concurrent callback with another event id then waits on the unique key and
        // replays the committed payment instead of crediting the wallet twice.
        users.recharge(request.userId(), request.amountCents(), "PAYMENT:" + request.eventId());
        notifications.enqueue(created.userId(), "PAYMENT:" + created.id(), "PAYMENT", "payment=" + created.id());
        return view(created);
    }

    @Transactional(readOnly = true)
    public PaymentView get(long paymentId, long currentUserId, boolean admin) {
        Payment payment = payments.findById(paymentId).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "payment not found"));
        if (!admin && payment.userId() != currentUserId) throw new BizException(ErrorCode.FORBIDDEN, "payment belongs to another user");
        return view(payment);
    }

    private PaymentView sameRequest(Payment payment, PaymentCallbackRequest request) {
        if (payment.userId() != request.userId() || payment.amountCents() != request.amountCents()
                || !payment.providerPaymentId().equals(request.providerPaymentId())) {
            throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT, "payment event was reused with different parameters");
        }
        return view(payment);
    }

    private PaymentView view(Payment payment) {
        return new PaymentView(payment.id(), payment.provider(), payment.providerPaymentId(), payment.callbackEventId(),
                payment.userId(), payment.amountCents(), payment.status());
    }
}
