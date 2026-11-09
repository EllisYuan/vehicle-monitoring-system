package com.vms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vms.support.IntegrationContainers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AC03 HTTP 部分：停用、改密、重置、撤权、单会话替换与到期后旧授权立即失效。
 */
@SpringBootTest(properties = {
        "vms.bootstrap-admin.username=bootstrap-admin",
        "vms.bootstrap-admin.password=Bootstrap-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
})
@Import({IntegrationContainers.class, AccountSessionTest.PasswordRaceConfiguration.class})
@AutoConfigureMockMvc
class AccountSessionTest {

    static final String ADMIN_PASSWORD = "Bootstrap-Pass-2026";
    static final String SUBJECT_PASSWORD = "Subject-Pass-2026";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PausingPasswordHasher pausingHasher;
    @Autowired
    ObjectMapper objectMapper;

    long fleetA;
    long subjectId;
    String adminToken;
    String subjectToken;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM sys_user_fleet");
        jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap-admin'");
        jdbc.update("DELETE FROM fleet");
        jdbc.update("UPDATE sys_user SET failed_attempts = 0, locked_until = NULL");
        adminToken = login("bootstrap-admin", ADMIN_PASSWORD);
        fleetA = createFleet("FL-A", "一队");
        subjectId = createUser("subject-1", SUBJECT_PASSWORD, "FLEET_LEADER", List.of(fleetA));
        subjectToken = login("subject-1", SUBJECT_PASSWORD);
    }

    @Test
    void disablingAccountInvalidatesExistingSessionOnNextHttpRequest() throws Exception {
        mvc.perform(get("/api/v1/fleets").header("satoken", subjectToken)).andExpect(status().isOk());

        mvc.perform(put("/api/v1/users/" + subjectId + "/status").header("satoken", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mvc.perform(get("/api/v1/fleets").header("satoken", subjectToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void passwordResetInvalidatesExistingSession() throws Exception {
        mvc.perform(post("/api/v1/users/" + subjectId + "/password-reset").header("satoken", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"Reset-Pass-2026\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/fleets").header("satoken", subjectToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));

        String newToken = login("subject-1", "Reset-Pass-2026");
        mvc.perform(get("/api/v1/fleets").header("satoken", newToken)).andExpect(status().isOk());
    }

    @Test
    void changingOwnPasswordInvalidatesSessionAndRequiresReLogin() throws Exception {
        mvc.perform(post("/api/v1/auth/password").header("satoken", subjectToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", SUBJECT_PASSWORD,
                                "newPassword", "Changed-Pass-2026"))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/fleets").header("satoken", subjectToken))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("subject-1", SUBJECT_PASSWORD)))
                .andExpect(status().isUnauthorized());
        login("subject-1", "Changed-Pass-2026");
    }

    @Test
    void ownPasswordChangeRollsBackWhenAuditCannotBeWritten() throws Exception {
        jdbc.execute("RENAME TABLE audit_log TO audit_log_unavailable");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    mvc.perform(post("/api/v1/auth/password").header("satoken", subjectToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "oldPassword", SUBJECT_PASSWORD,
                                    "newPassword", "Changed-Pass-2026")))))
                    .isInstanceOf(jakarta.servlet.ServletException.class);
        } finally {
            jdbc.execute("RENAME TABLE audit_log_unavailable TO audit_log");
        }
        login("subject-1", SUBJECT_PASSWORD);
    }

    @Test
    void wrongCurrentPasswordIsRejectedWithoutChangingPassword() throws Exception {
        mvc.perform(post("/api/v1/auth/password").header("satoken", subjectToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", "not-the-password",
                                "newPassword", "Changed-Pass-2026"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OLD_PASSWORD"));

        login("subject-1", SUBJECT_PASSWORD);
    }

    @Test
    void fleetRevocationInvalidatesSessionAndShrinksScope() throws Exception {
        long fleetB = createFleet("FL-B", "二队");
        mvc.perform(put("/api/v1/users/" + subjectId + "/fleets").header("satoken", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("fleetIds", List.of(fleetB)))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/fleets").header("satoken", subjectToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));

        String renewed = login("subject-1", SUBJECT_PASSWORD);
        mvc.perform(get("/api/v1/fleets").header("satoken", renewed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fleetCode").value("FL-B"));
    }

    @Test
    void laterLoginReplacesEarlierSessionWithClearMessage() throws Exception {
        String newer = login("subject-1", SUBJECT_PASSWORD);

        mvc.perform(get("/api/v1/fleets").header("satoken", subjectToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REPLACED"));
        mvc.perform(get("/api/v1/fleets").header("satoken", newer)).andExpect(status().isOk());
    }

    @Test
    void accountChangesAndLoginsAreAuditedWithoutSecrets() throws Exception {
        mvc.perform(post("/api/v1/users/" + subjectId + "/password-reset").header("satoken", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"Reset-Pass-2026\"}"))
                .andExpect(status().isNoContent());
        login("subject-1", "Reset-Pass-2026");

        // 只认本次重置目标产生的审计行，避免 setUp 中的建号写审计让断言空转
        String target = "sys_user:" + subjectId;
        List<Map<String, Object>> accountRows = jdbc.queryForList(
                "SELECT category, action, result, target, detail FROM audit_log WHERE target = ? ORDER BY id",
                target);
        assertThat(accountRows).anyMatch(r -> "ACCOUNT".equals(r.get("category"))
                && "RESET_PASSWORD".equals(r.get("action")));

        List<Map<String, Object>> allRows = jdbc.queryForList(
                "SELECT category, action, result, detail FROM audit_log ORDER BY id");
        String flattened = allRows.toString();
        assertThat(flattened).doesNotContain(SUBJECT_PASSWORD, "Reset-Pass-2026", "$2a$");
        if (subjectToken != null) {
            assertThat(flattened).doesNotContain(subjectToken);
        }
    }

    @Test
    void passwordChangeCannotOverwriteAnAdministratorReset() throws Exception {
        pausingHasher.pauseNextChange();
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var change = pool.submit(() -> mvc.perform(post("/api/v1/auth/password")
                            .header("satoken", subjectToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "oldPassword", SUBJECT_PASSWORD,
                                    "newPassword", "Concurrent-Change-2026"))))
                    .andReturn().getResponse().getStatus());
            try {
                assertThat(pausingHasher.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                mvc.perform(post("/api/v1/users/" + subjectId + "/password-reset")
                                .header("satoken", adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"newPassword\":\"Reset-Wins-2026\"}"))
                        .andExpect(status().isNoContent());
            } finally {
                pausingHasher.release.countDown();
            }
            assertThat(change.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(409);
        }
        login("subject-1", "Reset-Wins-2026");
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class PasswordRaceConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        PausingPasswordHasher pausingPasswordHasher() {
            return new PausingPasswordHasher();
        }
    }

    static class PausingPasswordHasher extends com.vms.identity.security.PasswordHasher {
        private java.util.concurrent.CountDownLatch entered;
        private java.util.concurrent.CountDownLatch release;

        void pauseNextChange() {
            entered = new java.util.concurrent.CountDownLatch(1);
            release = new java.util.concurrent.CountDownLatch(1);
        }

        @Override
        public String hash(String rawPassword) {
            if ("Concurrent-Change-2026".equals(rawPassword)) {
                entered.countDown();
                try {
                    if (!release.await(20, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Password race test timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return super.hash(rawPassword);
        }
    }

    private long createFleet(String code, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/fleets").header("satoken", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("fleetCode", code, "fleetName", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("id").asLong();
    }

    private long createUser(String username, String password, String role, List<Long> fleetIds) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/users").header("satoken", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", password,
                                "roleCode", role, "fleetIds", fleetIds))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("id").asLong();
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return body.path("token").asText();
    }

    private String loginBody(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("username", username, "password", password));
    }
}
