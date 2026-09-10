package com.yu.errand.config;

import com.yu.errand.repository.AccountRepository;
import com.yu.errand.repository.UserRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminBootstrap {
    private final GrabProperties properties;
    private final UserRepository users;
    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrap(GrabProperties properties, UserRepository users, AccountRepository accounts,
                          PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.users = users;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureAdmin() {
        String username = properties.getAdmin().getUsername();
        String password = properties.getAdmin().getPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) return;
        var existing = users.findByUsername(username.trim());
        if (existing.isEmpty()) {
            long id = users.insertAdmin("Project Admin", username.trim(), passwordEncoder.encode(password));
            accounts.ensureAccounts(id);
        } else if (!"ADMIN".equals(existing.get().role())) {
            users.updateRole(existing.get().id(), "ADMIN");
        }
    }
}
