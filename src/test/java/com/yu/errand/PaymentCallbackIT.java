package com.yu.errand;

import com.yu.errand.controller.dto.PaymentCallbackRequest;
import com.yu.errand.common.BizException;
import com.yu.errand.payment.SandboxPaymentProvider;
import com.yu.errand.repository.NotificationRepository;
import com.yu.errand.service.NotificationService;
import com.yu.errand.service.PaymentService;
import com.yu.errand.service.ReconService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentCallbackIT extends IntegrationTestBase {
    @Autowired private PaymentService payments;
    @Autowired private SandboxPaymentProvider provider;
    @Autowired private NotificationRepository notifications;
    @Autowired private NotificationService notificationService;
    @Autowired private ReconService recon;

    @Test
    void repeatedCallbackOnlyCreditsOnceAndCreatesOneNotification() {
        long userId = user("payment-callback-user");
        PaymentCallbackRequest unsigned = new PaymentCallbackRequest("sandbox-payment-1", "payment-event-1", userId,
                2500, Instant.now().getEpochSecond(), "unsigned");
        PaymentCallbackRequest signed = new PaymentCallbackRequest(unsigned.providerPaymentId(), unsigned.eventId(),
                unsigned.userId(), unsigned.amountCents(), unsigned.timestampEpochSeconds(), provider.sign(unsigned));

        long firstId = payments.callback(signed).id();
        for (int index = 0; index < 1000; index++) {
            assertEquals(firstId, payments.callback(signed).id());
        }

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_payment WHERE callback_event_id='payment-event-1'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM t_ledger_entry WHERE biz_type='RECHARGE'", Integer.class));
        assertEquals(1, notifications.countByEventKey("PAYMENT:" + firstId));
        assertEquals(1, jdbc.queryForObject("SELECT JSON_VALID(payload_json) FROM t_notification WHERE event_key=?", Integer.class,
                "PAYMENT:" + firstId));
        assertTrue(recon.run().passed());
    }

    @Test
    void rejectsInvalidOrExpiredCallbacksAndKeepsOutOfOrderProviderEventsIdempotent() {
        long userId = user("payment-event-user");
        long now = Instant.now().getEpochSecond();
        PaymentCallbackRequest invalid = new PaymentCallbackRequest(
                "sandbox-payment-invalid", "payment-event-invalid", userId, 1200, now, "bad-signature");
        BizException invalidSignature = assertThrows(BizException.class, () -> payments.callback(invalid));
        assertEquals("UNAUTHORIZED", invalidSignature.errorCode().code());

        PaymentCallbackRequest expiredUnsigned = new PaymentCallbackRequest(
                "sandbox-payment-expired", "payment-event-expired", userId, 1200, now - 3600, "unsigned");
        PaymentCallbackRequest expired = new PaymentCallbackRequest(
                expiredUnsigned.providerPaymentId(), expiredUnsigned.eventId(), expiredUnsigned.userId(),
                expiredUnsigned.amountCents(), expiredUnsigned.timestampEpochSeconds(), provider.sign(expiredUnsigned));
        BizException expiredCallback = assertThrows(BizException.class, () -> payments.callback(expired));
        assertEquals("UNAUTHORIZED", expiredCallback.errorCode().code());

        PaymentCallbackRequest lateEventUnsigned = new PaymentCallbackRequest(
                "sandbox-payment-out-of-order", "payment-event-late", userId, 1800, now, "unsigned");
        PaymentCallbackRequest lateEvent = new PaymentCallbackRequest(
                lateEventUnsigned.providerPaymentId(), lateEventUnsigned.eventId(), lateEventUnsigned.userId(),
                lateEventUnsigned.amountCents(), lateEventUnsigned.timestampEpochSeconds(), provider.sign(lateEventUnsigned));
        PaymentCallbackRequest originalEventUnsigned = new PaymentCallbackRequest(
                lateEventUnsigned.providerPaymentId(), "payment-event-original", userId, 1800, now, "unsigned");
        PaymentCallbackRequest originalEvent = new PaymentCallbackRequest(
                originalEventUnsigned.providerPaymentId(), originalEventUnsigned.eventId(), originalEventUnsigned.userId(),
                originalEventUnsigned.amountCents(), originalEventUnsigned.timestampEpochSeconds(), provider.sign(originalEventUnsigned));

        long paymentId = payments.callback(lateEvent).id();
        assertEquals(paymentId, payments.callback(originalEvent).id());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_payment WHERE provider_payment_id=?", Integer.class,
                "sandbox-payment-out-of-order"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM t_ledger_entry WHERE biz_type='RECHARGE'", Integer.class));
        assertEquals(1, notifications.countByEventKey("PAYMENT:" + paymentId));
    }
}
