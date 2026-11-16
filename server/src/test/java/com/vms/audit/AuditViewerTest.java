package com.vms.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vms.support.IntegrationContainers;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
 * US12、AC02 审计权限项：管理员只读查询前序票实际写入的登录与关键写操作审计。
 */
@SpringBootTest(properties = {
        "vms.bootstrap-admin.username=bootstrap-admin",
        "vms.bootstrap-admin.password=Bootstrap-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
})
@Import(IntegrationContainers.class)
@AutoConfigureMockMvc
class AuditViewerTest {

    static final String ADMIN_PASSWORD = "Bootstrap-Pass-2026";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;

    String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM notification_outbox");
        jdbc.update("DELETE FROM sys_user_fleet");
        jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap-admin'");
        jdbc.update("DELETE FROM fleet");
        jdbc.update("UPDATE sys_user SET failed_attempts = 0, locked_until = NULL");
        adminToken = login("bootstrap-admin", ADMIN_PASSWORD);
    }

    @Test
    void adminQueriesLoginAndWriteAuditWrittenByPriorTickets() throws Exception {
        loginExpectingFailure("ghost-user", "Wrong-Pass-2026");
        long fleetId = createFleet("AUD-1", "审计一队");

        mvc.perform(get("/api/v1/audit/logs").header("satoken", adminToken)
                        .param("actor", "ghost-user")
                        .param("result", "FAILURE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].category").value("LOGIN"))
                .andExpect(jsonPath("$.items[0].action").value("LOGIN"))
                .andExpect(jsonPath("$.items[0].actorName").value("ghost-user"))
                .andExpect(jsonPath("$.items[0].target").value("auth"))
                .andExpect(jsonPath("$.items[0].result").value("FAILURE"))
                .andExpect(jsonPath("$.items[0].detail").value("UNKNOWN_ACCOUNT"))
                .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty());

        mvc.perform(get("/api/v1/audit/logs").header("satoken", adminToken)
                        .param("category", "FLEET")
                        .param("action", "CREATE")
                        .param("actor", "bootstrap-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].target").value("fleet:" + fleetId))
                .andExpect(jsonPath("$.items[0].actorName").value("bootstrap-admin"))
                .andExpect(jsonPath("$.items[0].result").value("SUCCESS"));
    }

    @Test
    void monitorAndFleetLeaderAreRejectedByServer() throws Exception {
        createUser("audit-monitor", "Monitor-Pass-2026", "MONITOR");
        createUser("audit-leader", "Leader-Pass-2026", "FLEET_LEADER");
        String monitorToken = login("audit-monitor", "Monitor-Pass-2026");
        String leaderToken = login("audit-leader", "Leader-Pass-2026");

        for (String token : java.util.List.of(monitorToken, leaderToken)) {
            mvc.perform(get("/api/v1/audit/logs").header("satoken", token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(get("/api/v1/audit/operation-records").header("satoken", token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void detailMasksCredentialSamplesAndLoginPasswordNeverAppears() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        insertAudit(now, "ACCOUNT", "RESET_PASSWORD", "sys_user:999", "SUCCESS", "bootstrap-admin",
                "password=Sup3r-Secret-1, authCode=abcd1234efgh, token=tok-9988, wsTicket=ws-7766");
        insertAudit(now, "ACCOUNT", "RESET_PASSWORD", "sys_user:998", "SUCCESS", "bootstrap-admin",
                "{\"newPassword\":\"Json-Secret-2\",\"satoken\":\"sat-4455\"}");
        loginExpectingFailure("cred-user", "Submitted-Pass-999");

        MvcResult masked = mvc.perform(get("/api/v1/audit/logs").header("satoken", adminToken)
                        .param("actor", "bootstrap-admin").param("category", "ACCOUNT"))
                .andExpect(status().isOk())
                .andReturn();
        String maskedBody = masked.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(maskedBody)
                .doesNotContain("Sup3r-Secret-1", "abcd1234efgh", "tok-9988", "ws-7766",
                        "Json-Secret-2", "sat-4455")
                .contains("password=***", "authCode=***", "token=***", "wsTicket=***");

        MvcResult loginRows = mvc.perform(get("/api/v1/audit/logs").header("satoken", adminToken)
                        .param("actor", "cred-user").param("category", "LOGIN"))
                .andExpect(status().isOk())
                .andReturn();
        String loginBody = loginRows.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(loginBody).doesNotContain("Submitted-Pass-999").contains("UNKNOWN_ACCOUNT");
    }

    @Test
    void timeWindowPagingAndOrderingAreBoundedAndMatchDirectRows() throws Exception {
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        insertAudit(now.minusDays(10), "FLEET", "UPDATE", "fleet:old", "SUCCESS", "aud-pager", "seq=old");
        insertAudit(now.minusHours(3), "FLEET", "UPDATE", "fleet:newer", "SUCCESS", "aud-pager", "seq=1");
        insertAudit(now.minusHours(2), "FLEET", "UPDATE", "fleet:newest", "SUCCESS", "aud-pager", "seq=2");
        insertAudit(now.minusHours(1), "FLEET", "UPDATE", "fleet:latest", "SUCCESS", "aud-pager", "seq=3");

        mvc.perform(get("/api/v1/audit/logs").header("satoken", adminToken)
                        .param("actor", "aud-pager").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].target").value("fleet:latest"))
                .andExpect(jsonPath("$.items[1].target").value("fleet:newest"));

        mvc.perform(get("/api/v1/audit/logs").header("satoken", adminToken)
                        .param("actor", "aud-pager").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].target").value("fleet:newer"));

        mvc.perform(get("/api/v1/audit/logs").header("satoken", adminToken)
                        .param("actor", "aud-pager")
                        .param("from", now.minusDays(11).toString())
                        .param("to", now.minusDays(9).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].target").value("fleet:old"));

        Long direct = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE actor_name = 'aud-pager'", Long.class);
        assertThat(direct).isEqualTo(4L);
    }

    @Test
    void invalidOrUnboundedQueriesAreRejectedByServer() throws Exception {
        mvc.perform(get("/api/v1/audit/logs").header("satoken", adminToken).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));
        mvc.perform(get("/api/v1/audit/logs").header("satoken", adminToken).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        mvc.perform(get("/api/v1/audit/logs").header("satoken", adminToken)
                        .param("page", "501").param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAGE_OFFSET_TOO_LARGE"));
        mvc.perform(get("/api/v1/audit/logs").header("satoken", adminToken)
                        .param("from", "2026-01-01T00:00:00").param("to", "2026-03-01T00:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIME_RANGE_TOO_WIDE"));
        mvc.perform(get("/api/v1/audit/logs").header("satoken", adminToken)
                        .param("from", "not-a-time"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
        mvc.perform(get("/api/v1/audit/logs").header("satoken", adminToken)
                        .param("actor", "a".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIT_FILTER"));
    }

    @Test
    void auditApiExposesNoWriteOperations() throws Exception {
        for (var request : java.util.List.of(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/audit/logs"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/audit/logs"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/audit/logs"))) {
            mvc.perform(request.header("satoken", adminToken))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

    @Test
    void operationRecordsReflectNotificationOutboxSourceFacts() throws Exception {
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        jdbc.update("""
                INSERT INTO notification_outbox
                    (event_id, event_type, aggregate_id, version, status, retry_at, attempts, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, "evt-dead-1", "POSITION", 42L, 7L, "DEAD", now, 10, now);
        jdbc.update("""
                INSERT INTO notification_outbox
                    (event_id, event_type, aggregate_id, version, status, retry_at, attempts, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, "evt-pending-1", "ALARM", 43L, 1L, "PENDING", now.plusSeconds(5), 0, now);

        mvc.perform(get("/api/v1/audit/operation-records").header("satoken", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outboxCounts.DEAD").value(1))
                .andExpect(jsonPath("$.outboxCounts.PENDING").value(1))
                .andExpect(jsonPath("$.outboxCounts.RETRY").value(0))
                .andExpect(jsonPath("$.outboxCounts.DONE").value(0))
                .andExpect(jsonPath("$.deadOutbox.length()").value(1))
                .andExpect(jsonPath("$.deadOutbox[0].eventId").value("evt-dead-1"))
                .andExpect(jsonPath("$.deadOutbox[0].eventType").value("POSITION"))
                .andExpect(jsonPath("$.deadOutbox[0].aggregateId").value(42))
                .andExpect(jsonPath("$.deadOutbox[0].attempts").value(10))
                .andExpect(jsonPath("$.deadOutbox[0].updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.generatedAt").isNotEmpty());

        Long directDead = jdbc.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE status = 'DEAD'", Long.class);
        assertThat(directDead).isEqualTo(1L);
    }

    @Test
    void adminSessionLosesAuditAccessImmediatelyAfterRevocation() throws Exception {
        MvcResult created = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/users").header("satoken", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "audit-admin-2",
                                "password", "Audit-Admin-Pass-2026",
                                "roleCode", "ADMIN"))))
                .andExpect(status().isCreated())
                .andReturn();
        long userId = objectMapper.readTree(created.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("id").asLong();
        String secondAdminToken = login("audit-admin-2", "Audit-Admin-Pass-2026");
        mvc.perform(get("/api/v1/audit/logs").header("satoken", secondAdminToken))
                .andExpect(status().isOk());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/users/" + userId + "/status").header("satoken", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/audit/logs").header("satoken", secondAdminToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void boundedQueriesUseIndexAndDoNotBlockBusinessWrites() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 100; i++) {
            insertAudit(now.minusMinutes(i), "FLEET", "UPDATE", "fleet:bulk-" + i,
                    "SUCCESS", "aud-bulk", "seq=" + i);
        }
        List<Map<String, Object>> plan = jdbc.queryForList(
                "EXPLAIN SELECT count(*) FROM audit_log WHERE created_at >= ? AND created_at < ?",
                now.minusHours(1), now.plusMinutes(1));
        assertThat(plan.getFirst().get("key")).isEqualTo("idx_audit_log_created_at");

        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/api/v1/audit/logs").header("satoken", adminToken)
                            .param("size", "100").param("page", "0"))
                    .andExpect(status().isOk());
        }
        long fleetId = createFleet("AUD-W", "查询与写入并存");
        assertThat(fleetId).isPositive();
    }
    private void insertAudit(LocalDateTime createdAt, String category, String action, String target,
                             String result, String actorName, String detail) {
        jdbc.update("""
                INSERT INTO audit_log
                    (actor_id, actor_name, category, action, target, result, source_ip, detail, created_at)
                VALUES (NULL, ?, ?, ?, ?, ?, NULL, ?, ?)
                """, actorName, category, action, target, result, detail, createdAt);
    }
    private void createUser(String username, String password, String roleCode) throws Exception {
        mvc.perform(post("/api/v1/users").header("satoken", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", password, "roleCode", roleCode))))
                .andExpect(status().isCreated());
    }
    private void loginExpectingFailure(String username, String password) throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", password))))
                .andExpect(status().isUnauthorized());
    }

    private long createFleet(String code, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/fleets").header("satoken", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fleetCode", code, "fleetName", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("id").asLong();
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return body.path("token").asText();
    }
}





