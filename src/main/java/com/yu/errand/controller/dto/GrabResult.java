package com.yu.errand.controller.dto;

import com.yu.errand.domain.model.ErrandOrder;

public record GrabResult(boolean won, ErrandOrder order, String reason) {}

