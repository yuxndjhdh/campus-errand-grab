package com.yu.errand.controller;

import com.yu.errand.common.ApiResponse;
import com.yu.errand.controller.dto.CreateUserRequest;
import com.yu.errand.controller.dto.RechargeRequest;
import com.yu.errand.controller.dto.UserView;
import com.yu.errand.controller.dto.WalletView;
import com.yu.errand.service.UserService;
import com.yu.errand.security.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService users;
    private final CurrentUserService currentUser;

    public UserController(UserService users, CurrentUserService currentUser) {
        this.users = users;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ApiResponse<UserView> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.ok(users.create(request.nickname()));
    }

    @PostMapping("/{id}/recharge")
    public ApiResponse<WalletView> recharge(@PathVariable long id, @Valid @RequestBody RechargeRequest request) {
        if (!currentUser.isAdmin()) {
            throw new com.yu.errand.common.BizException(com.yu.errand.common.ErrorCode.FORBIDDEN,
                    "recharge callback must be performed by an administrator");
        }
        return ApiResponse.ok(users.recharge(id, request.amountCents(), request.idemKey()));
    }

    @PostMapping("/me/recharge")
    public ApiResponse<WalletView> rechargeSelf(@Valid @RequestBody RechargeRequest request) {
        return ApiResponse.ok(users.recharge(currentUser.id(), request.amountCents(), request.idemKey()));
    }

    @GetMapping("/{id}/wallet")
    public ApiResponse<WalletView> wallet(@PathVariable long id) {
        currentUser.requireSelf(id);
        return ApiResponse.ok(users.wallet(id));
    }
}
