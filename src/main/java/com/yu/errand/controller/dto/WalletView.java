package com.yu.errand.controller.dto;

import com.yu.errand.domain.model.Account;
import com.yu.errand.domain.model.LedgerEntry;

import java.util.List;

public record WalletView(long userId, List<Account> accounts, List<LedgerEntry> ledger) {}

