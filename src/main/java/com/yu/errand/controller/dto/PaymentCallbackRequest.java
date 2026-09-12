package com.yu.errand.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record PaymentCallbackRequest(@NotBlank String providerPaymentId,
                                     @NotBlank String eventId,
                                     @Min(1) long userId,
                                     @Min(1) long amountCents,
                                     @Min(1) long timestampEpochSeconds,
                                     @NotBlank String signature) {}
