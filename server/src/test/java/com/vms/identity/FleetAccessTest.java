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
 * AC02/AC03（HTTP）与 I3：三角色、两个车队、服务端功能权限与数据范围。
 */
@SpringBootTest(properties = {
        "vms.bootstrap-admin.username=bootstrap-admin",
        "vms.bootstrap-admin.password=Bootstrap-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
})
@Import(IntegrationContainers.class)
@AutoConfigureMockMvc
class FleetAccessTest {

    static final String ADMIN_PASSWORD = "Bootstrap-Pass-2026";
    static final String MONITOR_PASSWORD = "Monitor-Pass-2026";
    static final String LEADER_PASSWORD = "Leader-Pass-2026";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    cn.dev33.satoken.config.SaTokenConfig saTokenConfig;

    long fleetA;
    long fleetB;
    String adminToken;
    String monitorToken;
    String leaderToken;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM sys_user_fleet");
        jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap-admin'");
        jdbc.update("DELETE FROM fleet");
        jdbc.update("UPDATE sys_user SET failed_attempts = 0, locked_until = NULL WHERE username = 'bootstrap-admin'");
        adminToken = login("bootstrap-admin", ADMIN_PASSWORD);
        fleetA = createFleet("FL-A", "一队");
        fleetB = createFleet("FL-B", "二队");
        createUser("monitor-1", MONITOR_PASSWORD, "MONITOR", List.of());
        createUser("leader-1", LEADER_PASSWORD, "FLEET_LEADER", List.of(fleetA, fleetB));
        monitorToken = login("monitor-1", MONITOR_PASSWORD);
        leaderToken = login("leader-1", LEADER_PASSWORD);
    }

    @Test
    void sessionIsAbsoluteEightHoursWithoutSlidingRenewal() {
        // 生产配置：8 小时绝对到期，不随活跃续期；短窗口行为由 SessionExpiryTest 实测
        assertThat(saTokenConfig.getTimeout()).isEqualTo(28800L);
        assertThat(saTokenConfig.getActiveTimeout()).isEqualTo(-1L);
        assertThat(saTokenConfig.getAutoRenew()).isFalse();
        assertThat(saTokenConfig.getIsConcurrent()).isFalse();
    }

    @Test
    void adminCreatesAccountsWithExactlyOneRoleAndLeaderScopedToBothFleets() throws Exception {
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT username, role_code, status FROM sys_user ORDER BY username");
        assertThat(users).extracting(r -> r.get("role_code"))
                .containsExactlyInAnyOrder("ADMIN", "MONITOR", "FLEET_LEADER");
        assertThat(users).extracting(r -> r.get("status")).containsOnly("ENABLED");

        List<String> leaderFleetCodes = jdbc.queryForList(
                "SELECT f.fleet_code FROM sys_user_fleet uf JOIN fleet f ON f.id = uf.fleet_id "
                        + "WHERE uf.user_id = (SELECT id FROM sys_user WHERE username = 'leader-1') "
                        + "ORDER BY f.fleet_code", String.class);
        assertThat(leaderFleetCodes).containsExactly("FL-A", "FL-B");
    }

    @Test
    void fleetLeaderListContainsOnlyAuthorizedFleets() throws Exception {
        mvc.perform(get("/api/v1/fleets").header("satoken", leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        jdbc.update("DELETE FROM sys_user_fleet WHERE user_id = (SELECT id FROM sys_user WHERE username = 'leader-1') "
                + "AND fleet_id = ?", fleetB);
        mvc.perform(get("/api/v1/fleets").header("satoken", leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fleetCode").value("FL-A"));
    }

    @Test
    void fleetLeaderCrossFleetDetailIsRejectedWithoutLeakingFleetData() throws Exception {
        jdbc.update("DELETE FROM sys_user_fleet WHERE user_id = (SELECT id FROM sys_user WHERE username = 'leader-1') "
                + "AND fleet_id = ?", fleetB);

        MvcResult result = mvc.perform(get("/api/v1/fleets/" + fleetB).header("satoken", leaderToken))
                .andExpect(status().isForbidden())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("FL-B", "二队");
        assertThat(body).contains("FORBIDDEN");

        mvc.perform(get("/api/v1/fleets/" + fleetA).header("satoken", leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fleetCode").value("FL-A"));
    }

    @Test
    void clientSuppliedFleetFilterCannotWidenLeaderScope() throws Exception {
        jdbc.update("DELETE FROM sys_user_fleet WHERE user_id = (SELECT id FROM sys_user WHERE username = 'leader-1') "
                + "AND fleet_id = ?", fleetB);

        mvc.perform(get("/api/v1/fleets").param("fleetId", String.valueOf(fleetB))
                        .header("satoken", leaderToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fleets").param("fleetId", String.valueOf(fleetA))
                        .header("satoken", leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void monitorReadsAllFleetsButCannotWriteOrManageAccounts() throws Exception {
        mvc.perform(get("/api/v1/fleets").header("satoken", monitorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(post("/api/v1/fleets").header("satoken", monitorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fleetCode\":\"FL-C\",\"fleetName\":\"三队\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/v1/fleets/" + fleetA).header("satoken", monitorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/users").header("satoken", monitorToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/users").header("satoken", leaderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void fleetLeaderCannotCreateOrModifyFleets() throws Exception {
        mvc.perform(post("/api/v1/fleets").header("satoken", leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fleetCode\":\"FL-C\",\"fleetName\":\"三队\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/fleets/" + fleetA).header("satoken", leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fleetName\":\"改\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateFleetCodeIsRejectedAndExplained() throws Exception {
        mvc.perform(post("/api/v1/fleets").header("satoken", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fleetCode\":\"FL-A\",\"fleetName\":\"重复\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FLEET_CODE_EXISTS"));
    }

    @Test
    void accountAndAuthorizationChangesAreAuditedWithoutSecrets() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/users").header("satoken", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "audited-user",
                                "password", "Audited-Pass-2026",
                                "roleCode", "FLEET_LEADER",
                                "fleetIds", List.of(fleetA)))))
                .andExpect(status().isCreated())
                .andReturn();
        long createdId = objectMapper.readTree(
                created.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("id").asLong();
        String target = "sys_user:" + createdId;

        // 只认本次创建产生的审计行，避免 setUp 中的建号写审计让断言空转
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT category, action, result, actor_name, target, detail FROM audit_log "
                        + "WHERE target = ? ORDER BY id", target);
        assertThat(rows).anyMatch(r -> "ACCOUNT".equals(r.get("category"))
                && "CREATE".equals(r.get("action"))
                && "bootstrap-admin".equals(r.get("actor_name")));
        assertThat(rows).anyMatch(r -> "AUTHORIZATION".equals(r.get("category"))
                && "ASSIGN_FLEETS".equals(r.get("action")));
        assertThat(rows.toString()).doesNotContain("Audited-Pass-2026", "$2a$");
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

    private void createUser(String username, String password, String role, List<Long> fleetIds) throws Exception {
        mvc.perform(post("/api/v1/users").header("satoken", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", password, "roleCode", role, "fleetIds", fleetIds))))
                .andExpect(status().isCreated());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return body.path("token").asText();
    }
}
