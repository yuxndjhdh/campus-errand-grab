package com.yu.errand.controller.dto;

import com.yu.errand.domain.model.User;

public record UserView(long id, String nickname, String role, String username) {
    public static UserView of(User user) { return new UserView(user.id(), user.nickname(), user.role(), user.username()); }
}
