package com.yu.errand.service;

import com.yu.errand.common.BizException;
import com.yu.errand.common.ErrorCode;
import com.yu.errand.controller.dto.TokenView;
import com.yu.errand.security.JwtService;
import com.yu.errand.security.LoginAttemptGuard;
import com.yu.errand.security.SecurityAuditLogger;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserService users;
    private final JwtService jwt;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptGuard loginAttempts;
    private final SecurityAuditLogger audit;

    public AuthService(UserService users, JwtService jwt, PasswordEncoder passwordEncoder,
                       LoginAttemptGuard loginAttempts, SecurityAuditLogger audit) {
        this.users = users;
        this.jwt = jwt;
        this.passwordEncoder = passwordEncoder;
        this.loginAttempts = loginAttempts;
        this.audit = audit;
    }

    public TokenView login(String username, String password) {
        return login(username, password, "unknown");
    }

    public TokenView login(String username, String password, String remoteAddress) {
        String normalizedUsername = username == null ? "" : username.trim();
        if (!loginAttempts.allow(normalizedUsername, remoteAddress)) {
            audit.loginFailed(normalizedUsername, remoteAddress, "rate_limited");
            throw new BizException(ErrorCode.RATE_LIMITED, "too many failed login attempts");
        }
        try {
            var user = users.requireUserByUsername(normalizedUsername);
            if (!passwordEncoder.matches(password, users.passwordHash(user.id()))) {
                loginAttempts.recordFailure(normalizedUsername, remoteAddress);
                audit.loginFailed(normalizedUsername, remoteAddress, "invalid_credentials");
                throw new BizException(ErrorCode.UNAUTHORIZED, "invalid username or password");
            }
            loginAttempts.clearAccount(normalizedUsername);
            audit.loginSucceeded(normalizedUsername, remoteAddress);
            return new TokenView(jwt.issue(user), "Bearer", user.id(), user.username(), user.role());
        } catch (BizException ex) {
            if (ex.errorCode() == ErrorCode.NOT_FOUND) {
                loginAttempts.recordFailure(normalizedUsername, remoteAddress);
                audit.loginFailed(normalizedUsername, remoteAddress, "invalid_credentials");
                throw new BizException(ErrorCode.UNAUTHORIZED, "invalid username or password");
            }
            throw ex;
        }
    }
}
