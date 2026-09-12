package com.yu.errand.payment;

import com.yu.errand.controller.dto.PaymentCallbackRequest;

public interface PaymentProvider {
    String name();
    void verify(PaymentCallbackRequest request);
}
