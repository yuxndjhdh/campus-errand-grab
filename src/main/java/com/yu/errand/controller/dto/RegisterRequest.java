package com.yu.errand.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(@NotBlank @Size(max = 128) String username,
                              @NotBlank @Size(max = 64) String nickname,
                              @NotBlank @Size(min = 8, max = 128) String password) {}
