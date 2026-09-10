package com.yu.errand.controller.dto;

import jakarta.validation.constraints.Min;

public record RechargeRequest(@Min(1) long amountCents, String idemKey) {}

