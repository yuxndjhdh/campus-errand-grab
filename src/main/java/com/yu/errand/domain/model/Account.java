package com.yu.errand.domain.model;

import com.yu.errand.domain.AccountType;

public record Account(long userId, AccountType accountType, long balanceCents) {}

