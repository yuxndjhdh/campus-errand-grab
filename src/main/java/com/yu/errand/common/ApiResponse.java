package com.yu.errand.common;

public record ApiResponse<T>(String code, String message, T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "success", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>("OK", "success", null);
    }
}

