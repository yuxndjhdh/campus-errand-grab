package com.yu.errand.service;

import com.yu.errand.common.BizException;
import com.yu.errand.common.ErrorCode;
import com.yu.errand.controller.dto.TokenView;
import com.yu.errand.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserService users;
    private final JwtService jwt;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserService users, JwtService jwt, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.jwt = jwt;
        this.passwordEncoder = passwordEncoder;
    }

    public TokenView login(String username, String password) {
        var user = users.requireUserByUsername(username.trim());
        if (!passwordEncoder.matches(password, users.passwordHash(user.id()))) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "invalid username or password");
        }
        return new TokenView(jwt.issue(user), "Bearer", user.id(), user.username(), user.role());
    }
}
