package com.vms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.vms.support.IntegrationContainers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "vms.bootstrap-admin.username=bootstrap-admin",
        "vms.bootstrap-admin.password=Bootstrap-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
})
@Import(IntegrationContainers.class)
@AutoConfigureMockMvc
class LoginFlowTest {

    static final String USERNAME = "bootstrap-admin";
    static final String PASSWORD = "Bootstrap-Pass-2026";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;

    String token;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM sys_user_fleet");
        jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap-admin'");
        jdbc.update("DELETE FROM fleet");
        jdbc.update("DELETE FROM audit_log WHERE category <> 'BOOTSTRAP'");
        // 锁定与失败计数属于 02 的独立验收，这里只保证 01 的登录骨架不受累计失败影响
        jdbc.update("UPDATE sys_user SET failed_attempts = 0, locked_until = NULL");
        token = null;
    }

    @Test
    void bootstrapAdminExistsOnceWithHashedPasswordAndAdminRole() {
        List<Map<String, Object>> admins = jdbc.queryForList(
                "SELECT username, password_hash, role_code, status FROM sys_user");
        assertThat(admins).hasSize(1);
        assertThat(admins.get(0).get("username")).isEqualTo(USERNAME);
        assertThat(admins.get(0).get("role_code")).isEqualTo("ADMIN");
        assertThat(admins.get(0).get("status")).isEqualTo("ENABLED");
        assertThat((String) admins.get(0).get("password_hash"))
                .doesNotContain(PASSWORD)
                .startsWith("$2a$");
    }

    @Test
    void validCredentialsOpenSessionAndUserEndpoint() throws Exception {
        login();

        mvc.perform(get("/api/v1/auth/me").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME))
                .andExpect(jsonPath("$.roleCode").value("ADMIN"));
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void sessionTokenIsAcceptedOnlyFromHeaderNotCookie() throws Exception {
        login();

        mvc.perform(get("/api/v1/auth/me").header("satoken", token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(new jakarta.servlet.http.Cookie("satoken", token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noDefaultPasswordIsAccepted() throws Exception {
        for (String candidate : List.of("admin", "123456", "password", "Bootstrap-Pass-2026!")) {
            mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("admin", candidate)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void wrongPasswordAndUnknownAccountAreIndistinguishable() throws Exception {
        MvcResult wrongPassword = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USERNAME, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andReturn();
        MvcResult unknownAccount = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("no-such-account", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String firstBody = wrongPassword.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String secondBody = unknownAccount.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(firstBody).isEqualTo(secondBody);
        assertThat(firstBody).doesNotContain(USERNAME, "no-such-account", "wrong-password");
    }

    @Test
    void logoutInvalidatesTokenForBusinessEndpoints() throws Exception {
        login();

        mvc.perform(post("/api/v1/auth/logout").header("satoken", token))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/me").header("satoken", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginAndLogoutAreAuditedWithoutSecrets() throws Exception {
        login();
        mvc.perform(post("/api/v1/auth/logout").header("satoken", token))
                .andExpect(status().isNoContent());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT category, action, result, actor_name, source_ip, detail FROM audit_log ORDER BY id");
        assertThat(rows).anyMatch(r -> "LOGIN".equals(r.get("category")) && "SUCCESS".equals(r.get("result")));
        assertThat(rows).anyMatch(r -> "LOGOUT".equals(r.get("category")) && "SUCCESS".equals(r.get("result")));
        assertAuditedWithoutSecrets(rows);
    }

    @Test
    void failedLoginsAreAuditedWithoutSecrets() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USERNAME, "wrong-password")))
                .andExpect(status().isUnauthorized());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT category, action, result, actor_name, detail FROM audit_log");
        assertThat(rows).anyMatch(r -> "LOGIN".equals(r.get("category"))
                && "FAILURE".equals(r.get("result"))
                && USERNAME.equals(r.get("actor_name")));
        assertAuditedWithoutSecrets(rows);
    }

    @Test
    void bootstrapIsRecordedInAudit() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT category, action, result FROM audit_log WHERE category = 'BOOTSTRAP'");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("result")).isEqualTo("SUCCESS");
    }

    @Test
    void loginResponseNeverEchoesSecretMaterial() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(PASSWORD, "password", "passwordHash");
    }

    private void login() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        token = body.path("token").asText();
        assertThat(token).isNotBlank();
    }

    private String loginBody(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("username", username, "password", password));
    }

    private void assertAuditedWithoutSecrets(List<Map<String, Object>> rows) {
        String flattened = rows.toString();
        assertThat(flattened).doesNotContain(PASSWORD, "wrong-password", "$2a$");
        if (token != null) {
            assertThat(flattened).doesNotContain(token);
        }
    }
}
