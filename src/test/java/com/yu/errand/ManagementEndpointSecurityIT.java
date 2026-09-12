package com.yu.errand;

import com.yu.errand.security.JwtService;
import com.yu.errand.service.UserService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ManagementEndpointSecurityIT extends IntegrationTestBase {
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService users;
    @Autowired private JwtService jwt;

    @Test
    void adminEndpointsRequireAdminForAnonymousAndRegularUsers() throws Exception {
        var user = users.register("management-user", "management-user", "management-password");
        var admin = users.register("management-admin", "management-admin", "management-password");
        jdbc.update("UPDATE t_user SET role='ADMIN' WHERE id=?", admin.id());
        String userToken = jwt.issue(users.requireUser(user.id()));
        String adminToken = jwt.issue(users.requireUser(admin.id()));

        for (String path : new String[]{"/api/admin/stats", "/api/admin/recon/report", "/api/admin/outbox/dead"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(path).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void everyAdminRouteKeepsTheAnonymousUserAndRegularUserBoundary() throws Exception {
        var user = users.register("management-route-user", "management-route-user", "management-password");
        var admin = users.register("management-route-admin", "management-route-admin", "management-password");
        jdbc.update("UPDATE t_user SET role='ADMIN' WHERE id=?", admin.id());
        String userToken = jwt.issue(users.requireUser(user.id()));
        String adminToken = jwt.issue(users.requireUser(admin.id()));

        String[][] routes = {
                {"GET", "/api/admin/stats"},
                {"GET", "/api/admin/recon/report"},
                {"GET", "/api/admin/outbox/dead"},
                {"POST", "/api/admin/recon/run"},
                {"POST", "/api/admin/outbox/999999/replay"}
        };
        for (String[] route : routes) {
            mockMvc.perform(request(route[0], route[1])).andExpect(status().isUnauthorized());
            mockMvc.perform(request(route[0], route[1]).header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isForbidden());
            int adminStatus = route[1].contains("999999/replay") ? 404 : 200;
            mockMvc.perform(request(route[0], route[1]).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().is(adminStatus));
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String method, String path) {
        if ("POST".equals(method)) {
            return post(path).contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return get(path);
    }

    @Test
    void applicationRequestsHaveTraceIdsAndExpectedAuthenticationBoundary() throws Exception {
        var user = users.register("management-trace-user", "management-trace-user", "management-password");
        String userToken = jwt.issue(users.requireUser(user.id()));

        var ping = mockMvc.perform(get("/api/ping")).andExpect(status().isOk()).andReturn();
        var protectedRoute = mockMvc.perform(get("/api/orders")).andExpect(status().isUnauthorized()).andReturn();
        var authenticatedRoute = mockMvc.perform(get("/api/orders")
                .header("Authorization", "Bearer " + userToken)).andExpect(status().isOk()).andReturn();

        assertTrue(ping.getResponse().getHeader("X-Trace-Id") != null);
        assertTrue(protectedRoute.getResponse().getHeader("X-Trace-Id") != null);
        assertTrue(authenticatedRoute.getResponse().getHeader("X-Trace-Id") != null);
        assertEquals(200, ping.getResponse().getStatus());
    }
}
