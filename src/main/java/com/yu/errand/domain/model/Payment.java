package com.yu.errand.domain.model;

public record Payment(long id, String provider, String providerPaymentId, String callbackEventId,
                      long userId, long amountCents, String status) {}
