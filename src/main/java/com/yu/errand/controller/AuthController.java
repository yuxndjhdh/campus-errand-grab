package com.yu.errand.controller;

import com.yu.errand.common.ApiResponse;
import com.yu.errand.controller.dto.LoginRequest;
import com.yu.errand.controller.dto.RegisterRequest;
import com.yu.errand.controller.dto.TokenView;
import com.yu.errand.controller.dto.UserView;
import com.yu.errand.service.AuthService;
import com.yu.errand.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserService users;
    private final AuthService auth;

    public AuthController(UserService users, AuthService auth) {
        this.users = users;
        this.auth = auth;
    }

    @PostMapping("/register")
    public ApiResponse<UserView> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(users.register(request.username(), request.nickname(), request.password()));
    }

    @PostMapping("/login")
    public ApiResponse<TokenView> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(auth.login(request.username(), request.password()));
    }
}
