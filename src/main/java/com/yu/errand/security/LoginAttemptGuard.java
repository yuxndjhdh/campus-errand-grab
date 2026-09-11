package com.yu.errand.security;

import com.yu.errand.config.GrabProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginAttemptGuard {
    private static final Logger log = LoggerFactory.getLogger(LoginAttemptGuard.class);
    private static final String ACCOUNT_PREFIX = "security:login:account:";
    private static final String IP_PREFIX = "security:login:ip:";

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script;
    private final GrabProperties properties;
    private final ConcurrentHashMap<String, Window> fallback = new ConcurrentHashMap<>();

    public LoginAttemptGuard(StringRedisTemplate redis,
                             @Qualifier("loginAttemptScript") DefaultRedisScript<Long> loginAttemptScript,
                             GrabProperties properties) {
        this.redis = redis;
        this.script = loginAttemptScript;
        this.properties = properties;
    }

    public boolean allow(String username, String remoteAddress) {
        String accountKey = accountKey(username);
        String ipKey = ipKey(remoteAddress);
        try {
            Long result = redis.execute(script, List.of(accountKey, ipKey),
                    String.valueOf(properties.getSecurity().getLoginFailureLimit()),
                    String.valueOf(properties.getSecurity().getLoginIpFailureLimit()),
                    String.valueOf(properties.getSecurity().getLoginWindowSeconds()),
                    "0");
            return result == null || result == 1L;
        } catch (DataAccessException ex) {
            log.warn("login rate limiter unavailable; using local fallback");
            return fallbackAllow(accountKey, ipKey, System.currentTimeMillis());
        }
    }

    public void recordFailure(String username, String remoteAddress) {
        String accountKey = accountKey(username);
        String ipKey = ipKey(remoteAddress);
        try {
            redis.execute(script, List.of(accountKey, ipKey),
                    String.valueOf(properties.getSecurity().getLoginFailureLimit()),
                    String.valueOf(properties.getSecurity().getLoginIpFailureLimit()),
                    String.valueOf(properties.getSecurity().getLoginWindowSeconds()),
                    "1");
        } catch (DataAccessException ex) {
            log.warn("login failure counter unavailable; using local fallback");
            fallbackRecordFailure(accountKey, ipKey, System.currentTimeMillis());
        }
    }

    public void clearAccount(String username) {
        String key = accountKey(username);
        try {
            redis.delete(key);
        } catch (DataAccessException ex) {
            log.warn("login rate limiter cleanup unavailable; retaining local fallback");
        }
        fallback.remove(key);
    }

    private synchronized boolean fallbackAllow(String accountKey, String ipKey, long now) {
        long windowMillis = Duration.ofSeconds(properties.getSecurity().getLoginWindowSeconds()).toMillis();
        Window account = active(accountKey, now);
        Window ip = active(ipKey, now);
        return (account == null || account.count < properties.getSecurity().getLoginFailureLimit())
                && (ip == null || ip.count < properties.getSecurity().getLoginIpFailureLimit());
    }

    private synchronized void fallbackRecordFailure(String accountKey, String ipKey, long now) {
        long windowMillis = Duration.ofSeconds(properties.getSecurity().getLoginWindowSeconds()).toMillis();
        increment(accountKey, now, windowMillis);
        increment(ipKey, now, windowMillis);
    }

    private Window active(String key, long now) {
        Window current = fallback.get(key);
        if (current == null) {
            return null;
        }
        if (current.expiresAt <= now) {
            fallback.remove(key, current);
            return null;
        }
        return current;
    }

    private Window increment(String key, long now, long windowMillis) {
        Window current = fallback.get(key);
        if (current == null || current.expiresAt <= now) {
            current = new Window(now + windowMillis, 0);
            fallback.put(key, current);
        }
        current.count++;
        return current;
    }

    private String accountKey(String username) {
        return ACCOUNT_PREFIX + digest(username == null ? "" : username.trim().toLowerCase(Locale.ROOT));
    }

    private String ipKey(String remoteAddress) {
        return IP_PREFIX + digest(remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress);
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static final class Window {
        private final long expiresAt;
        private int count;

        private Window(long expiresAt, int count) {
            this.expiresAt = expiresAt;
            this.count = count;
        }
    }
}
