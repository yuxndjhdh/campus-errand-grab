package com.yu.errand.domain.model;

import com.yu.errand.domain.AccountType;

import java.time.LocalDateTime;

public record LedgerEntry(long id, String bizType, long bizId, long userId,
                          AccountType accountType, long amountCents, LocalDateTime createdAt) {}

