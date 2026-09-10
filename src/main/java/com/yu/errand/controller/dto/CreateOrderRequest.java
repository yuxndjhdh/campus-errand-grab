package com.yu.errand.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateOrderRequest(@NotBlank String title, String detail,
                                 @Min(1) long rewardCents, Long claimTtlSeconds) {}
