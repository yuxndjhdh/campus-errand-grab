package com.yu.errand.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record DisputeRequest(@NotBlank String reason) {}
