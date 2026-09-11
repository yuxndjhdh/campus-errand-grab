package com.yu.errand.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SecurityAuditLogger {
    private static final Logger log = LoggerFactory.getLogger(SecurityAuditLogger.class);

    public void loginSucceeded(String username, String remoteAddress) {
        log.info("security_event=login_success username={} remoteAddress={}", safe(username), safe(remoteAddress));
    }

    public void loginFailed(String username, String remoteAddress, String reason) {
        log.warn("security_event=login_failure username={} remoteAddress={} reason={}",
                safe(username), safe(remoteAddress), safe(reason));
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replaceAll("[\\r\\n\\t]", "_").substring(0, Math.min(value.length(), 128));
    }
}
