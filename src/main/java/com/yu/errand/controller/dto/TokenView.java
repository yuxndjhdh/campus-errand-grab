package com.yu.errand.controller.dto;

public record TokenView(String accessToken, String tokenType, long userId, String username, String role) {}
