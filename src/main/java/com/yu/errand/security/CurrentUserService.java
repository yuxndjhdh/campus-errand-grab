package com.yu.errand.security;

import com.yu.errand.common.BizException;
import com.yu.errand.common.ErrorCode;
import com.yu.errand.domain.model.User;
import com.yu.errand.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {
    private final UserService users;

    public CurrentUserService(UserService users) { this.users = users; }

    public User get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getName())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "authentication is required");
        }
        return users.requireUserByUsername(authentication.getName());
    }

    public long id() { return get().id(); }
    public boolean isAdmin() { return "ADMIN".equals(get().role()); }

    public void requireSelf(long userId) {
        if (!isAdmin() && id() != userId) throw new BizException(ErrorCode.FORBIDDEN, "only the current user may access this resource");
    }
}
