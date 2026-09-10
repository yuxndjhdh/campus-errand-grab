package com.yu.errand.repository;

public record IdempotencyRecord(String opKey, String opType, long bizId, String requestHash, String responseJson) {}
