package com.yu.errand.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class RequestHasher {
    private RequestHasher() {}

    public static String sha256(String normalizedRequest) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedRequest.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
