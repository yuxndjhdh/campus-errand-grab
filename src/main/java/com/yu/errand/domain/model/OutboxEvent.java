package com.yu.errand.domain.model;

import java.time.LocalDateTime;

public record OutboxEvent(long id, String eventType, long bizId, String payloadJson,
                          String status, int retryCount, LocalDateTime nextRetryAt,
                          LocalDateTime lockedUntil, String lastError, LocalDateTime createdAt) {}
