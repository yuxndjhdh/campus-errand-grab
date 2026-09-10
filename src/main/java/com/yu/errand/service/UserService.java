package com.yu.errand.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.errand.common.BizException;
import com.yu.errand.common.ErrorCode;
import com.yu.errand.controller.dto.UserView;
import com.yu.errand.controller.dto.WalletView;
import com.yu.errand.domain.AccountType;
import com.yu.errand.domain.LedgerBizType;
import com.yu.errand.domain.model.User;
import com.yu.errand.repository.AccountRepository;
import com.yu.errand.repository.IdempotencyRepository;
import com.yu.errand.repository.IdempotencyState;
import com.yu.errand.repository.LedgerRepository;
import com.yu.errand.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

@Service
public class UserService {
    private final UserRepository users;
    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final IdempotencyRepository idempotency;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository users, AccountRepository accounts, LedgerRepository ledger,
                       IdempotencyRepository idempotency, ObjectMapper objectMapper, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserView create(String nickname) {
        String normalizedNickname = nickname.trim();
        String generatedUsername = "legacy-" + UUID.randomUUID();
        long id = users.insert(normalizedNickname, generatedUsername, passwordEncoder.encode(UUID.randomUUID().toString()));
        accounts.ensureAccounts(id);
        return new UserView(id, normalizedNickname, "USER", generatedUsername);
    }

    @Transactional
    public UserView register(String username, String nickname, String password) {
        if (username == null || username.isBlank() || password == null || password.length() < 8) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "username and password are required; password must have 8 characters");
        }
        String normalizedUsername = username.trim();
        String normalizedNickname = nickname.trim();
        if (users.findByUsername(normalizedUsername).isPresent()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "username already exists");
        }
        long id = users.insert(normalizedNickname, normalizedUsername, passwordEncoder.encode(password));
        accounts.ensureAccounts(id);
        return new UserView(id, normalizedNickname, "USER", normalizedUsername);
    }

    @Transactional
    public WalletView recharge(long userId, long amountCents, String requestedKey) {
        User target = requireUser(userId);
        if (!"USER".equals(target.role())) {
            throw new BizException(ErrorCode.SYSTEM_ACCOUNT_FORBIDDEN, "system accounts cannot receive ordinary recharge");
        }
        if (amountCents <= 0) throw new BizException(ErrorCode.VALIDATION_FAILED, "amountCents must be positive");
        accounts.ensureAccounts(userId);
        accounts.ensureAccounts(1);
        String requestKey = (requestedKey == null || requestedKey.isBlank()) ? UUID.randomUUID().toString() : requestedKey.trim();
        String key = "RECHARGE:" + requestKey;
        String requestHash = RequestHasher.sha256(userId + "|" + amountCents);
        if (idempotency.start(key, LedgerBizType.RECHARGE.name(), userId, requestHash) == IdempotencyState.REPLAY) {
            return replayWallet(idempotency.find(key).orElseThrow().responseJson(), userId);
        }
        accounts.lockUsers(Math.min(1, userId), Math.max(1, userId));
        accounts.change(1, AccountType.AVAILABLE, -amountCents);
        if (accounts.change(userId, AccountType.AVAILABLE, amountCents) != 1) {
            throw new BizException(ErrorCode.NOT_FOUND, "account not found");
        }
        long bizId = Math.abs(System.nanoTime());
        ledger.insert(LedgerBizType.RECHARGE.name(), bizId, 1, AccountType.AVAILABLE, -amountCents);
        ledger.insert(LedgerBizType.RECHARGE.name(), bizId, userId, AccountType.AVAILABLE, amountCents);
        WalletView result = wallet(userId);
        try {
            idempotency.complete(key, objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot save idempotency response", ex);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public WalletView wallet(long userId) {
        requireUser(userId);
        return new WalletView(userId, accounts.findByUserId(userId), ledger.findByUser(userId));
    }

    public User requireUser(long userId) {
        return users.find(userId).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "user not found: " + userId));
    }

    public User requireUserByUsername(String username) {
        return users.findByUsername(username).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "user not found"));
    }

    public boolean passwordMatches(long userId, String rawPassword) {
        return passwordEncoder.matches(rawPassword, users.passwordHash(userId));
    }

    public String passwordHash(long userId) {
        return users.passwordHash(userId);
    }

    private WalletView replayWallet(String responseJson, long userId) {
        if (responseJson == null || responseJson.isBlank()) return wallet(userId);
        try {
            return objectMapper.readValue(responseJson, WalletView.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot read idempotency response", ex);
        }
    }

    public User requireBusinessUser(long userId) {
        User user = requireUser(userId);
        if (!"USER".equals(user.role())) {
            throw new BizException(ErrorCode.SYSTEM_ACCOUNT_FORBIDDEN, "system accounts cannot use ordinary order flows");
        }
        return user;
    }

    public User requireRole(long userId, String role) {
        User user = requireUser(userId);
        if (!role.equals(user.role())) {
            throw new BizException(ErrorCode.SYSTEM_ACCOUNT_FORBIDDEN, "user does not have required system role: " + role);
        }
        return user;
    }
}
