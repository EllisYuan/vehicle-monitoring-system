package com.vms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vms.identity.auth.LoginRateLimiter;
import com.vms.support.IntegrationContainers;
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
import tools.jackson.databind.ObjectMapper;

/**
 * 来源速率限制（工程补充）：同一来源短时间内的失败登录超过上限后拒绝；
 * 成功登录不占配额，避免正常用户被误伤。
 * 本测试把上限缩短为 3 次以执行验证；生产默认 20 次/60 秒。
 */
@SpringBootTest(properties = {
        "vms.bootstrap-admin.username=bootstrap-admin",
        "vms.bootstrap-admin.password=Bootstrap-Pass-2026",
        "vms.login.rate-limit-max-attempts=3",
        "vms.login.rate-limit-window-seconds=60",
})
@Import(IntegrationContainers.class)
@AutoConfigureMockMvc
class LoginRateLimitTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    LoginRateLimiter loginRateLimiter;

    @BeforeEach
    void resetLimiter() {
        // 限速器是进程内单例；各用例独立从空窗口开始，避免用例间互相计数
        loginRateLimiter.reset();
    }

    @Test
    void failedAttemptsFromSameSourceAreThrottled() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("bootstrap-admin", "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("bootstrap-admin", "Bootstrap-Pass-2026")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
                .andExpect(jsonPath("$.retryAfter").isNumber());

        String audit = jdbc.queryForList("SELECT category, action, result, detail FROM audit_log")
                .toString();
        assertThat(audit).doesNotContain("wrong-password", "Bootstrap-Pass-2026", "$2a$");
    }

    @Test
    void concurrentRequestsCannotAllPassTheSourceLimit() throws Exception {
        int requests = 10;
        var ready = new java.util.concurrent.CountDownLatch(requests);
        var start = new java.util.concurrent.CountDownLatch(1);
        var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(requests)) {
            for (int i = 0; i < requests; i++) {
                int index = i;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mvc.perform(post("/api/v1/auth/login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body("unknown-" + index, "wrong-password")))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var statuses = new java.util.ArrayList<Integer>();
            for (var result : results) {
                statuses.add(result.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(statuses).filteredOn(status -> status == 401).hasSize(3);
            assertThat(statuses).filteredOn(status -> status == 429).hasSize(7);
        }
    }

    @Test
    void successfulLoginDoesNotConsumeTheFailedAttemptBudget() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("bootstrap-admin", "Bootstrap-Pass-2026")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("bootstrap-admin", "Bootstrap-Pass-2026")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("bootstrap-admin", "Bootstrap-Pass-2026")))
                .andExpect(status().isOk());
    }

    @Test
    void throttleResponseIsRecordedOnlyAfterFailuresAndLeavesNoCredentials() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("nobody", "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("nobody", "wrong-password")))
                .andExpect(status().isTooManyRequests());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT category, action, result, detail FROM audit_log WHERE category = 'LOGIN'");
        assertThat(rows).hasSizeGreaterThanOrEqualTo(3);
        assertThat(rows.toString()).doesNotContain("wrong-password", "$2a$");
    }

    private String body(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("username", username, "password", password));
    }
}
