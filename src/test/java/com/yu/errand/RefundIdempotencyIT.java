package com.yu.errand;

import com.yu.errand.controller.dto.CompensationRequest;
import com.yu.errand.controller.dto.DisputeRequest;
import com.yu.errand.controller.dto.PaymentCallbackRequest;
import com.yu.errand.controller.dto.RefundView;
import com.yu.errand.common.BizException;
import com.yu.errand.payment.SandboxPaymentProvider;
import com.yu.errand.repository.NotificationRepository;
import com.yu.errand.service.DisputeService;
import com.yu.errand.service.PaymentService;
import com.yu.errand.service.RefundService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefundIdempotencyIT extends IntegrationTestBase {
    @Autowired private PaymentService payments;
    @Autowired private RefundService refunds;
    @Autowired private DisputeService disputes;
    @Autowired private SandboxPaymentProvider provider;
    @Autowired private NotificationRepository notifications;

    @Test
    void repeatedRefundAndCompensationHaveOneFinancialEffectAndAuditTrail() {
        long userId = user("refund-user");
        PaymentCallbackRequest unsigned = new PaymentCallbackRequest("sandbox-payment-refund", "payment-event-refund", userId,
                3000, Instant.now().getEpochSecond(), "unsigned");
        PaymentCallbackRequest signed = new PaymentCallbackRequest(unsigned.providerPaymentId(), unsigned.eventId(),
                unsigned.userId(), unsigned.amountCents(), unsigned.timestampEpochSeconds(), provider.sign(unsigned));
        long paymentId = payments.callback(signed).id();

        RefundView firstRefund = refunds.refund(paymentId, userId, 3000, "refund-request-1");
        for (int index = 0; index < 999; index++) {
            assertEquals(firstRefund.id(), refunds.refund(paymentId, userId, 3000, "refund-request-1").id());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_refund WHERE request_key='refund-request-1'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM t_ledger_entry WHERE biz_type='REFUND'", Integer.class));
        assertEquals("REFUNDED", jdbc.queryForObject("SELECT status FROM t_payment WHERE id=?", String.class, paymentId));
        assertEquals(1, notifications.countByEventKey("REFUND:" + firstRefund.id()));
        BizException secondRefund = assertThrows(BizException.class,
                () -> refunds.refund(paymentId, userId, 3000, "refund-request-2"));
        assertEquals("ILLEGAL_TRANSITION", secondRefund.errorCode().code());

        long adminId = user("refund-admin");
        jdbc.update("UPDATE t_user SET role='ADMIN' WHERE id=?", adminId);
        var dispute = disputes.open(paymentId, userId, "sandbox dispute");
        disputes.compensate(dispute.id(), adminId,
                new CompensationRequest(1000, "compensation-request-1", "verified sandbox dispute"));
        disputes.compensate(dispute.id(), adminId,
                new CompensationRequest(1000, "compensation-request-1", "verified sandbox dispute"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_compensation WHERE request_key='compensation-request-1'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM t_ledger_entry WHERE biz_type='COMPENSATION'", Integer.class));
        assertEquals("RESOLVED", jdbc.queryForObject("SELECT status FROM t_dispute WHERE id=?", String.class, dispute.id()));
        assertTrue(jdbc.queryForObject("SELECT reason IS NOT NULL AND operator_user_id IS NOT NULL FROM t_compensation WHERE request_key='compensation-request-1'", Boolean.class));
    }
}
