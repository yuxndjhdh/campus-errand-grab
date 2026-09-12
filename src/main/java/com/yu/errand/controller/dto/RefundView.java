package com.yu.errand.controller.dto;

public record RefundView(long id, long paymentId, String requestKey, long userId, long amountCents, String status) {}
