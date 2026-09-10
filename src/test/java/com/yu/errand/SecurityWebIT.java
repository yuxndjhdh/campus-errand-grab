package com.yu.errand;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.errand.config.GrabProperties;
import com.yu.errand.domain.model.User;
import com.yu.errand.security.JwtService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SecurityWebIT extends IntegrationTestBase {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwt;
    @Autowired private GrabProperties properties;

    @Test
    void unauthenticatedWritesAndLegacyUserCreationAreRejected() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("unauthenticated")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"legacy\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedExpiredAndWrongSignatureTokensAreRejected() throws Exception {
        String username = "jwt-user";
        register(username, "jwt-password");
        String valid = login(username, "jwt-password");

        mockMvc.perform(post("/api/orders").header("Authorization", "Bearer malformed")
                        .contentType(MediaType.APPLICATION_JSON).content(orderJson("malformed")))
                .andExpect(status().isUnauthorized());
        String wrongSignature = valid.substring(0, valid.lastIndexOf('.') + 1) + "invalid-signature";
        mockMvc.perform(post("/api/orders").header("Authorization", "Bearer " + wrongSignature)
                        .contentType(MediaType.APPLICATION_JSON).content(orderJson("wrong signature")))
                .andExpect(status().isUnauthorized());

        long oldTtl = properties.getSecurity().getAccessTokenTtlSeconds();
        String expired;
        try {
            properties.getSecurity().setAccessTokenTtlSeconds(-1);
            long userId = jdbc.queryForObject("SELECT id FROM t_user WHERE username=?", Long.class, username);
            User user = users.requireUser(userId);
            expired = jwt.issue(user);
        } finally {
            properties.getSecurity().setAccessTokenTtlSeconds(oldTtl);
        }
        mockMvc.perform(post("/api/orders").header("Authorization", "Bearer " + expired)
                        .contentType(MediaType.APPLICATION_JSON).content(orderJson("expired")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userPermissionsCannotCrossWalletOrAdminBoundaries() throws Exception {
        String firstUsername = "wallet-first";
        String secondUsername = "wallet-second";
        register(firstUsername, "wallet-password-1");
        register(secondUsername, "wallet-password-2");
        String firstToken = login(firstUsername, "wallet-password-1");
        String secondToken = login(secondUsername, "wallet-password-2");
        long firstId = userId(firstUsername);

        mockMvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/{id}/wallet", firstId)
                        .header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/users/{id}/recharge", firstId)
                        .header("Authorization", "Bearer " + secondToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":1000,\"idemKey\":\"cross-user-recharge\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void currentIdentityControlsOrderActions() throws Exception {
        String publisherUsername = "identity-publisher";
        String takerUsername = "identity-taker";
        register(publisherUsername, "identity-publisher-password");
        register(takerUsername, "identity-taker-password");
        String publisherToken = login(publisherUsername, "identity-publisher-password");
        String takerToken = login(takerUsername, "identity-taker-password");
        postSelfRecharge(publisherToken, "identity-recharge");
        long orderId = createOrder(publisherToken, "identity order");
        assertEquals(userId(publisherUsername), jdbc.queryForObject(
                "SELECT publisher_id FROM t_errand_order WHERE id=?", Long.class, orderId));

        mockMvc.perform(post("/api/orders/{id}/grab", orderId)
                        .header("Authorization", "Bearer " + takerToken))
                .andExpect(status().isOk());
        assertEquals(userId(takerUsername), jdbc.queryForObject(
                "SELECT taker_id FROM t_errand_order WHERE id=?", Long.class, orderId));
        mockMvc.perform(post("/api/orders/{id}/deliver", orderId)
                        .header("Authorization", "Bearer " + publisherToken))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + takerToken))
                .andExpect(status().isConflict());
    }

    @Test
    void adminCanAccessAdminRoutesAndRechargeSpecificUser() throws Exception {
        String userUsername = "admin-target";
        String adminUsername = "web-admin";
        register(userUsername, "admin-target-password");
        register(adminUsername, "web-admin-password");
        long adminId = userId(adminUsername);
        jdbc.update("UPDATE t_user SET role='ADMIN' WHERE id=?", adminId);
        jdbc.update("UPDATE t_account SET balance_cents=100000 WHERE user_id=1 AND account_type='AVAILABLE'");
        String adminToken = login(adminUsername, "web-admin-password");
        long targetId = userId(userUsername);

        mockMvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/users/{id}/recharge", targetId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":1000,\"idemKey\":\"admin-recharge\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void registrationStoresBcryptAndLoginTokenWorksForLaterRequests() throws Exception {
        String username = "bcrypt-user";
        String password = "bcrypt-password";
        String registerResponse = register(username, password);
        long userId = userId(username);
        String hash = users.passwordHash(userId);
        assertTrue(hash.startsWith("$2"));
        assertFalse(registerResponse.contains(hash));

        String token = login(username, password);
        mockMvc.perform(get("/api/users/{id}/wallet", userId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private String register(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "nickname", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("data").path("accessToken").asText();
    }

    private long createOrder(String token, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(title)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private void postSelfRecharge(String token, String key) throws Exception {
        mockMvc.perform(post("/api/users/me/recharge")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amountCents", 10_000, "idemKey", key))))
                .andExpect(status().isOk());
    }

    private long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM t_user WHERE username=?", Long.class, username);
    }

    private String orderJson(String title) throws Exception {
        return objectMapper.writeValueAsString(Map.of("title", title, "rewardCents", 1000, "claimTtlSeconds", 120));
    }
}
