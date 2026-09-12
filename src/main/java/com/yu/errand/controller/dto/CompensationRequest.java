package com.yu.errand.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CompensationRequest(@Min(1) long amountCents, @NotBlank String requestKey,
                                  @NotBlank String reason) {}
