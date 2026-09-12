package com.yu.errand.domain.model;

public record Dispute(long id, long paymentId, long userId, String status, String reason,
                      String resolution, Long resolvedBy) {}
