package com.yu.errand.domain.model;

import com.yu.errand.domain.OrderStatus;

import java.time.LocalDateTime;

public record ErrandOrder(long id, long publisherId, Long takerId, String title, String detail,
                          long rewardCents, long commissionCents, OrderStatus status,
                          LocalDateTime claimDeadlineAt, LocalDateTime deliverDeadlineAt,
                          LocalDateTime grabbedAt, LocalDateTime deliveredAt,
                          LocalDateTime settledAt, LocalDateTime cancelledAt,
                          long version, LocalDateTime createdAt,
                          int settlementRetryCount, LocalDateTime settlementNextRetryAt,
                          String settlementLastError, boolean settlementDead) {}
