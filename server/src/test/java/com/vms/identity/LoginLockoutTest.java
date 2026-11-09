package com.vms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vms.infrastructure.ClockConfig;
import com.vms.support.IntegrationContainers;
import com.vms.support.MutableClock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * AC04：连续 5 次失败锁定 10 分钟；锁定前成功登录重置计数；错误响应不泄露凭证。
 * 锁定窗口通过可控业务时钟推进验证，生产配置不变。
 */
@SpringBootTest(properties = {
        "vms.bootstrap-admin.username=bootstrap-admin",
        "vms.bootstrap-admin.password=Bootstrap-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
})
@Import({IntegrationContainers.class, LoginLockoutTest.MutableClockConfig.class})
@AutoConfigureMockMvc
class LoginLockoutTest {

    static final String ADMIN_PASSWORD = "Bootstrap-Pass-2026";
    static final String SUBJECT_PASSWORD = "Subject-Pass-2026";

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(
                    LocalDateTime.of(2026, 11, 8, 9, 0).atZone(ClockConfig.BUSINESS_ZONE).toInstant(),
                    ClockConfig.BUSINESS_ZONE);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    MutableClock clock;

    String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM sys_user_fleet");
        jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap-admin'");
        jdbc.update("DELETE FROM fleet");
        jdbc.update("UPDATE sys_user SET failed_attempts = 0, locked_until = NULL");
        adminToken = login("bootstrap-admin", ADMIN_PASSWORD);
        createUser("lockme", SUBJECT_PASSWORD);
    }

    @Test
    void fiveConsecutiveFailuresLockForTenMinutesAndSuccessfulLoginResetsCount() throws Exception {
        // 4 次失败后成功登录：计数重置，不触发锁定
        for (int i = 0; i < 4; i++) {
            failedLogin("lockme", "wrong-password");
        }
        login("lockme", SUBJECT_PASSWORD);

        // 重置后再失败 4 次仍未锁定
        for (int i = 0; i < 4; i++) {
            failedLogin("lockme", "wrong-password");
        }
        login("lockme", SUBJECT_PASSWORD);

        // 连续 5 次失败后锁定：响应与凭证错误一致，不通过状态码暴露锁定或账号存在性
        for (int i = 0; i < 4; i++) {
            failedLogin("lockme", "wrong-password");
        }
        failedLogin("lockme", "wrong-password");

        MvcResult locked = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("lockme", SUBJECT_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn();
        assertThat(locked.getResponse().getHeader("Retry-After")).isNull();

        // 锁定窗口内即使凭证正确也拒绝
        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("lockme", SUBJECT_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // 10 分钟后恢复
        clock.advance(Duration.ofSeconds(2));
        login("lockme", SUBJECT_PASSWORD);
    }

    @Test
    void expiredLockStartsANewFailureCycle() throws Exception {
        for (int i = 0; i < 5; i++) {
            failedLogin("lockme", "wrong-password");
        }
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        for (int i = 0; i < 4; i++) {
            failedLogin("lockme", "wrong-password");
        }
        login("lockme", SUBJECT_PASSWORD);
    }

    @Test
    void lockAndFailureEventsAreAuditedWithoutSecrets() throws Exception {
        for (int i = 0; i < 5; i++) {
            failedLogin("lockme", "wrong-password");
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT category, action, result, detail FROM audit_log WHERE category = 'LOGIN' ORDER BY id");
        assertThat(rows).anyMatch(r -> "LOCK".equals(r.get("action")));
        assertThat(rows).filteredOn(r -> "FAILURE".equals(r.get("result"))).hasSizeGreaterThanOrEqualTo(5);
        String flattened = rows.toString();
        assertThat(flattened).doesNotContain(SUBJECT_PASSWORD, "wrong-password", "$2a$");
    }

    @Test
    void concurrentFailuresLockAfterTheThresholdWithoutExtendingTheLock() throws Exception {
        int threads = 10;
        var start = new java.util.concurrent.CountDownLatch(1);
        var results = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    failedLogin("lockme", "wrong-password");
                    return null;
                }));
            }
            start.countDown();
            for (var result : results) {
                result.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        }

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT failed_attempts, locked_until FROM sys_user WHERE username = 'lockme'");
        assertThat(((Number) row.get("failed_attempts")).intValue()).isEqualTo(5);
        assertThat(row.get("locked_until")).isNotNull();
        clock.advance(Duration.ofMinutes(9));
        failedLogin("lockme", "wrong-password");
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        login("lockme", SUBJECT_PASSWORD);
    }

    @Test
    void lockedResponseIsIndistinguishableFromUnknownAccount() throws Exception {
        for (int i = 0; i < 5; i++) {
            failedLogin("lockme", "wrong-password");
        }
        // 已存在且锁定、正确密码
        String lockedBody = failedBody("lockme", SUBJECT_PASSWORD);
        // 已存在且锁定、错误密码
        String lockedWrongBody = failedBody("lockme", "other-wrong-password");
        // 不存在账号
        String unknownBody = failedBody("no-such-account", "any-password");

        assertThat(lockedBody).isEqualTo(lockedWrongBody);
        assertThat(lockedBody).isEqualTo(unknownBody);
        assertThat(lockedBody).doesNotContain(SUBJECT_PASSWORD, "$2a$");
    }

    private String failedBody(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void failedLogin(String username, String password) throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("token").asText();
    }

    private void createUser(String username, String password) throws Exception {
        mvc.perform(post("/api/v1/users").header("satoken", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", password,
                                "roleCode", "MONITOR", "fleetIds", List.of()))))
                .andExpect(status().isCreated());
    }

    private String loginBody(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("username", username, "password", password));
    }
}
