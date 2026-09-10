package com.yu.errand.common;

public enum ErrorCode {
    UNAUTHORIZED(401, "UNAUTHORIZED"),
    NOT_FOUND(404, "NOT_FOUND"),
    FORBIDDEN(403, "FORBIDDEN"),
    VALIDATION_FAILED(400, "VALIDATION_FAILED"),
    INSUFFICIENT_BALANCE(409, "INSUFFICIENT_BALANCE"),
    ALREADY_TAKEN(409, "ALREADY_TAKEN"),
    EXPIRED(409, "EXPIRED"),
    SELF_GRAB(409, "SELF_GRAB"),
    ILLEGAL_TRANSITION(409, "ILLEGAL_TRANSITION"),
    GRAB_REJECTED(409, "GRAB_REJECTED"),
    SYSTEM_ACCOUNT_FORBIDDEN(409, "SYSTEM_ACCOUNT_FORBIDDEN"),
    IDEMPOTENCY_CONFLICT(409, "IDEMPOTENCY_CONFLICT"),
    RATE_LIMITED(429, "RATE_LIMITED"),
    IDEMPOTENT_REPLAY(200, "IDEMPOTENT_REPLAY"),
    INTERNAL_ERROR(500, "INTERNAL_ERROR");

    private final int httpStatus;
    private final String code;

    ErrorCode(int httpStatus, String code) {
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int httpStatus() { return httpStatus; }
    public String code() { return code; }
}
