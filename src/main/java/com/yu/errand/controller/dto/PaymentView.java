package com.yu.errand.controller.dto;

public record PaymentView(long id, String provider, String providerPaymentId, String callbackEventId,
                          long userId, long amountCents, String status) {}
