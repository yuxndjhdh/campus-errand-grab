package com.yu.errand.domain.model;

public record Refund(long id, long paymentId, String requestKey, long userId, long amountCents, String status) {}
