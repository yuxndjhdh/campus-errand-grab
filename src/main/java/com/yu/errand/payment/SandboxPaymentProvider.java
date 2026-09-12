package com.yu.errand.payment;

import com.yu.errand.common.BizException;
import com.yu.errand.common.ErrorCode;
import com.yu.errand.config.GrabProperties;
import com.yu.errand.controller.dto.PaymentCallbackRequest;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

@Component
public class SandboxPaymentProvider implements PaymentProvider {
    private final GrabProperties properties;

    public SandboxPaymentProvider(GrabProperties properties) { this.properties = properties; }

    @Override
    public String name() { return "SANDBOX"; }

    @Override
    public void verify(PaymentCallbackRequest request) {
        long skew = Math.abs(Instant.now().getEpochSecond() - request.timestampEpochSeconds());
        if (skew > properties.getPayment().getWebhookSkewSeconds()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "payment callback timestamp is outside replay window");
        }
        String canonical = request.eventId() + "|" + request.timestampEpochSeconds() + "|"
                + request.providerPaymentId() + "|" + request.userId() + "|" + request.amountCents();
        String expected = sign(canonical, properties.getPayment().getWebhookSecret());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                request.signature().getBytes(StandardCharsets.US_ASCII))) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "invalid payment callback signature");
        }
    }

    public String sign(PaymentCallbackRequest request) {
        String canonical = request.eventId() + "|" + request.timestampEpochSeconds() + "|"
                + request.providerPaymentId() + "|" + request.userId() + "|" + request.amountCents();
        return sign(canonical, properties.getPayment().getWebhookSecret());
    }

    private String sign(String input, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder result = new StringBuilder();
            for (byte value : mac.doFinal(input.getBytes(StandardCharsets.UTF_8))) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot sign sandbox payment event", ex);
        }
    }
}
