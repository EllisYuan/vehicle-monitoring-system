package com.vms.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vms.support.IntegrationContainers;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "vms.bootstrap-admin.username=archive-admin",
        "vms.bootstrap-admin.password=Archive-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
})
@Import({IntegrationContainers.class, ArchiveFlowTest.TimeConfiguration.class})
@AutoConfigureMockMvc
class ArchiveFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void administratorCanRegisterVehicleAndReadItBackWithItsInitialFleetHistory() throws Exception {
        String token = login("archive-admin", "Archive-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = create(token, "/fleets", Map.of("fleetCode", suffix, "fleetName", "模拟车队"));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", "模拟-" + suffix, "fleetId", fleet));
        JsonNode view = read(token, "/vehicles/" + vehicle);
        assertThat(view.path("plateNo").asText()).isEqualTo("模拟-" + suffix);
        assertThat(view.path("fleetId").asLong()).isEqualTo(fleet);
        assertThat(view.path("status").asText()).isEqualTo("ENABLED");
        JsonNode history = read(token, "/vehicles/" + vehicle + "/fleet-history");
        assertThat(history.size()).isEqualTo(1);
        assertThat(history.get(0).path("fleetId").asLong()).isEqualTo(fleet);
        assertThat(history.get(0).path("effectiveTo").isNull()).isTrue();
    }

    @Test
    void replacementAndUnbindingRetainHalfOpenIntervalsAndDisabledArchives() throws Exception {
        String token = login("archive-admin", "Archive-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = create(token, "/fleets", Map.of("fleetCode", suffix, "fleetName", "模拟车队"));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", suffix, "fleetId", fleet));
        long first = create(token, "/terminals", Map.of("terminalNo", terminalNo()));
        long second = create(token, "/terminals", Map.of("terminalNo", terminalNo()));
        change(token, "/vehicles/" + vehicle + "/terminal", Map.of("terminalId", first));
        clock.advance(java.time.Duration.ofSeconds(1));
        change(token, "/vehicles/" + vehicle + "/terminal", Map.of("terminalId", second));
        JsonNode bindings = read(token, "/vehicles/" + vehicle + "/terminal-bindings");
        assertThat(bindings.size()).isEqualTo(2);
        assertThat(bindings.get(0).path("effectiveTo").asText())
                .isEqualTo(bindings.get(1).path("effectiveFrom").asText());
        assertThat(bindings.get(1).path("effectiveTo").isNull()).isTrue();
        String boundary = bindings.get(1).path("effectiveFrom").asText();
        assertThat(jdbc.queryForObject("""
                SELECT terminal_id FROM terminal_vehicle_binding WHERE vehicle_id = ?
                AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
                """, Long.class, vehicle, boundary, boundary)).isEqualTo(second);
        clock.advance(java.time.Duration.ofSeconds(1));
        change(token, "/vehicles/" + vehicle + "/terminal", java.util.Collections.singletonMap("terminalId", null));
        change(token, "/vehicles/" + vehicle, Map.of("status", "DISABLED"));
        change(token, "/terminals/" + first, Map.of("status", "DISABLED"));
        assertThat(read(token, "/vehicles/" + vehicle).path("terminalId").isNull()).isTrue();
        assertThat(read(token, "/terminals/" + first).path("status").asText()).isEqualTo("DISABLED");
        assertThat(read(token, "/vehicles/" + vehicle + "/terminal-bindings").size()).isEqualTo(2);
    }

    @Test
    void occupiedAndDisabledTerminalsAreRejectedWithExplanations() throws Exception {
        String token = login("archive-admin", "Archive-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = create(token, "/fleets", Map.of("fleetCode", suffix, "fleetName", "模拟车队"));
        long a = create(token, "/vehicles", Map.of("plateNo", "A" + suffix, "fleetId", fleet));
        long b = create(token, "/vehicles", Map.of("plateNo", "B" + suffix, "fleetId", fleet));
        long terminal = create(token, "/terminals", Map.of("terminalNo", terminalNo()));
        change(token, "/terminals/" + terminal, Map.of("status", "DISABLED"));
        assertThat(rejectedChange(token, "/vehicles/" + a + "/terminal", Map.of("terminalId", terminal)))
                .isEqualTo("TERMINAL_DISABLED");
        change(token, "/terminals/" + terminal, Map.of("status", "ENABLED"));
        change(token, "/vehicles/" + a + "/terminal", Map.of("terminalId", terminal));
        assertThat(rejectedChange(token, "/vehicles/" + b + "/terminal", Map.of("terminalId", terminal)))
                .isEqualTo("TERMINAL_OCCUPIED");
        assertThat(read(token, "/vehicles/" + b).path("terminalId").isNull()).isTrue();
    }

    @Test
    void transferPreservesHistoryAndLimitsLeaderAndMonitorAccess() throws Exception {
        String admin = login("archive-admin", "Archive-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long a = create(admin, "/fleets", Map.of("fleetCode", "A" + suffix, "fleetName", "模拟一队"));
        long b = create(admin, "/fleets", Map.of("fleetCode", "B" + suffix, "fleetName", "模拟二队"));
        long vehicle = create(admin, "/vehicles", Map.of("plateNo", suffix, "fleetId", a));
        long terminal = create(admin, "/terminals", Map.of("terminalNo", terminalNo()));
        change(admin, "/vehicles/" + vehicle + "/terminal", Map.of("terminalId", terminal));
        create(admin, "/users", Map.of("username", "leader-" + suffix, "password", "Leader-Pass-2026",
                "roleCode", "FLEET_LEADER", "fleetIds", java.util.List.of(a)));
        create(admin, "/users", Map.of("username", "monitor-" + suffix, "password", "Monitor-Pass-2026",
                "roleCode", "MONITOR", "fleetIds", java.util.List.of()));
        String leader = login("leader-" + suffix, "Leader-Pass-2026");
        String monitor = login("monitor-" + suffix, "Monitor-Pass-2026");
        assertThat(read(leader, "/vehicles/" + vehicle).path("id").asLong()).isEqualTo(vehicle);
        // 完整绑定历史含转队前的其他车队终端，属未授权历史，车队长不可读
        mvc.perform(get("/api/v1/vehicles/" + vehicle + "/terminal-bindings").header("satoken", leader))
                .andExpect(status().isForbidden());
        assertThat(read(monitor, "/vehicles/" + vehicle + "/terminal-bindings").size()).isEqualTo(1);
        mvc.perform(get("/api/v1/vehicles").param("fleetId", String.valueOf(b)).header("satoken", leader))
                .andExpect(status().isForbidden());
        for (String token : java.util.List.of(leader, monitor)) {
            mvc.perform(put("/api/v1/vehicles/" + vehicle + "/fleet").header("satoken", token)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("fleetId", b))))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/terminals").header("satoken", token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"terminalNo\":\"00000000000000000001\"}"))
                    .andExpect(status().isForbidden());
        }
        clock.advance(java.time.Duration.ofSeconds(1));
        change(admin, "/vehicles/" + vehicle + "/fleet", Map.of("fleetId", b));
        JsonNode history = read(admin, "/vehicles/" + vehicle + "/fleet-history");
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).path("effectiveTo").asText())
                .isEqualTo(history.get(1).path("effectiveFrom").asText());
        assertThat(history.get(1).path("fleetId").asLong()).isEqualTo(b);
        assertThat(read(monitor, "/vehicles/" + vehicle).path("fleetId").asLong()).isEqualTo(b);
        for (String path : java.util.List.of("/vehicles/" + vehicle, "/vehicles/" + vehicle + "/fleet-history",
                "/vehicles/" + vehicle + "/terminal-bindings", "/terminals/" + terminal)) {
            mvc.perform(get("/api/v1" + path).header("satoken", leader)).andExpect(status().isForbidden());
        }
        assertThat(read(leader, "/vehicles").toString()).doesNotContain(suffix);
        assertThat(read(leader, "/terminals").size()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE category = 'VEHICLE' "
                + "AND action = 'TRANSFER' AND target = ?", Long.class, "vehicle:" + vehicle)).isEqualTo(1L);
    }

    @Test
    void clockRollbackAndSameMillisecondCannotRewriteIntervals() throws Exception {
        String token = login("archive-admin", "Archive-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = create(token, "/fleets", Map.of("fleetCode", suffix, "fleetName", "模拟车队"));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", suffix, "fleetId", fleet));
        long terminal = create(token, "/terminals", Map.of("terminalNo", terminalNo()));
        change(token, "/vehicles/" + vehicle + "/terminal", Map.of("terminalId", terminal));
        var unbind = java.util.Collections.singletonMap("terminalId", null);
        assertThat(rejectedChange(token, "/vehicles/" + vehicle + "/terminal", unbind))
                .isEqualTo("EFFECTIVE_TIME_NOT_ADVANCED");
        clock.advance(java.time.Duration.ofSeconds(-10));
        assertThat(rejectedChange(token, "/vehicles/" + vehicle + "/terminal", unbind))
                .isEqualTo("EFFECTIVE_TIME_NOT_ADVANCED");
        assertThat(read(token, "/vehicles/" + vehicle + "/terminal-bindings").size()).isEqualTo(1);
        clock.advance(java.time.Duration.ofSeconds(11));
    }

    @Test
    void concurrentRequestsCannotGiveOneTerminalToTwoVehicles() throws Exception {
        String token = login("archive-admin", "Archive-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = create(token, "/fleets", Map.of("fleetCode", suffix, "fleetName", "模拟车队"));
        long a = create(token, "/vehicles", Map.of("plateNo", "A" + suffix, "fleetId", fleet));
        long b = create(token, "/vehicles", Map.of("plateNo", "B" + suffix, "fleetId", fleet));
        long terminal = create(token, "/terminals", Map.of("terminalNo", terminalNo()));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentBind(start, token, a, terminal));
            var second = executor.submit(() -> concurrentBind(start, token, b, terminal));
            start.countDown();
            assertThat(java.util.List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(20, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM terminal_vehicle_binding WHERE terminal_id = ? "
                + "AND effective_to IS NULL", Long.class, terminal)).isEqualTo(1L);
    }

    @Test
    void concurrentRequestsCannotGiveOneVehicleTwoCurrentTerminals() throws Exception {
        String token = login("archive-admin", "Archive-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = create(token, "/fleets", Map.of("fleetCode", suffix, "fleetName", "模拟车队"));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", suffix, "fleetId", fleet));
        long a = create(token, "/terminals", Map.of("terminalNo", terminalNo()));
        long b = create(token, "/terminals", Map.of("terminalNo", terminalNo()));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentBind(start, token, vehicle, a));
            var second = executor.submit(() -> concurrentBind(start, token, vehicle, b));
            start.countDown();
            assertThat(java.util.List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(20, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(read(token, "/vehicles/" + vehicle + "/terminal-bindings").size()).isEqualTo(1);
    }

    @Test
    void concurrentTransfersRetainExactlyOneCurrentFleetInterval() throws Exception {
        String token = login("archive-admin", "Archive-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long a = create(token, "/fleets", Map.of("fleetCode", "A" + suffix, "fleetName", "模拟一队"));
        long b = create(token, "/fleets", Map.of("fleetCode", "B" + suffix, "fleetName", "模拟二队"));
        long c = create(token, "/fleets", Map.of("fleetCode", "C" + suffix, "fleetName", "模拟三队"));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", suffix, "fleetId", a));
        clock.advance(java.time.Duration.ofSeconds(1));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> toB = () -> {
                start.await();
                return mvc.perform(put("/api/v1/vehicles/" + vehicle + "/fleet").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("fleetId", b))))
                        .andReturn().getResponse().getStatus();
            };
            java.util.concurrent.Callable<Integer> toC = () -> {
                start.await();
                return mvc.perform(put("/api/v1/vehicles/" + vehicle + "/fleet").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("fleetId", c))))
                        .andReturn().getResponse().getStatus();
            };
            var first = executor.submit(toB);
            var second = executor.submit(toC);
            start.countDown();
            assertThat(java.util.List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(20, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        JsonNode history = read(token, "/vehicles/" + vehicle + "/fleet-history");
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).path("effectiveTo").asText())
                .isEqualTo(history.get(1).path("effectiveFrom").asText());
        assertThat(history.get(1).path("fleetId").asLong())
                .isEqualTo(read(token, "/vehicles/" + vehicle).path("fleetId").asLong());
    }

    @Test
    void manyConcurrentBindsStillLeaveExactlyOneCurrentBinding() throws Exception {
        String token = login("archive-admin", "Archive-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = create(token, "/fleets", Map.of("fleetCode", suffix, "fleetName", "模拟车队"));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", suffix, "fleetId", fleet));
        int contenders = 5;
        long[] terminals = new long[contenders];
        for (int i = 0; i < contenders; i++) {
            terminals[i] = create(token, "/terminals", Map.of("terminalNo", terminalNo()));
        }
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(contenders)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (long terminal : terminals) {
                futures.add(executor.submit(() -> concurrentBind(start, token, vehicle, terminal)));
            }
            start.countDown();
            for (var future : futures) {
                assertThat(future.get(30, java.util.concurrent.TimeUnit.SECONDS)).isIn(200, 409);
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM terminal_vehicle_binding WHERE vehicle_id = ? "
                + "AND effective_to IS NULL", Long.class, vehicle)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM terminal_vehicle_binding b1 "
                + "JOIN terminal_vehicle_binding b2 ON b1.vehicle_id = b2.vehicle_id AND b1.id < b2.id "
                + "WHERE b1.vehicle_id = ? AND b1.effective_from < COALESCE(b2.effective_to, '9999-12-31') "
                + "AND b2.effective_from < COALESCE(b1.effective_to, '9999-12-31')", Long.class, vehicle)).isZero();
    }

    @Test
    void transferringToTheCurrentFleetIsIdempotentEvenIfThatFleetIsDisabled() throws Exception {
        String token = login("archive-admin", "Archive-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = create(token, "/fleets", Map.of("fleetCode", suffix, "fleetName", "模拟车队"));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", suffix, "fleetId", fleet));
        change(token, "/fleets/" + fleet, Map.of("status", "DISABLED"));

        // 目标已是当前车队，不产生新区间也不因车队停用而失败
        change(token, "/vehicles/" + vehicle + "/fleet", Map.of("fleetId", fleet));
        assertThat(read(token, "/vehicles/" + vehicle + "/fleet-history").size()).isEqualTo(1);
    }

    @Test
    void updateWithoutFieldsWritesNoAuditEntry() throws Exception {
        String token = login("archive-admin", "Archive-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = create(token, "/fleets", Map.of("fleetCode", suffix, "fleetName", "模拟车队"));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", suffix, "fleetId", fleet));
        change(token, "/vehicles/" + vehicle, Map.of());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE category = 'VEHICLE' "
                + "AND action = 'UPDATE' AND target = ?", Long.class, "vehicle:" + vehicle)).isZero();
    }

    @Test
    void auditWriteFailureRollsBackBindingAndTransferTogetherWithTheirHistory() throws Exception {
        String token = login("archive-admin", "Archive-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long a = create(token, "/fleets", Map.of("fleetCode", "A" + suffix, "fleetName", "模拟一队"));
        long b = create(token, "/fleets", Map.of("fleetCode", "B" + suffix, "fleetName", "模拟二队"));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", suffix, "fleetId", a));
        long terminal = create(token, "/terminals", Map.of("terminalNo", terminalNo()));
        clock.advance(java.time.Duration.ofSeconds(1));
        jdbc.execute("RENAME TABLE audit_log TO archive_test_audit_unavailable");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> change(token,
                    "/vehicles/" + vehicle + "/terminal", Map.of("terminalId", terminal)))
                    .isInstanceOf(jakarta.servlet.ServletException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> change(token,
                    "/vehicles/" + vehicle + "/fleet", Map.of("fleetId", b)))
                    .isInstanceOf(jakarta.servlet.ServletException.class);
        } finally {
            jdbc.execute("RENAME TABLE archive_test_audit_unavailable TO audit_log");
        }
        assertThat(read(token, "/vehicles/" + vehicle).path("fleetId").asLong()).isEqualTo(a);
        assertThat(read(token, "/vehicles/" + vehicle).path("terminalId").isNull()).isTrue();
        assertThat(read(token, "/vehicles/" + vehicle + "/terminal-bindings").size()).isZero();
        assertThat(read(token, "/vehicles/" + vehicle + "/fleet-history").size()).isEqualTo(1);
    }

    @Test
    void databaseRejectsDuplicateCurrentReferencesAndDeletingReferencedArchives() throws Exception {
        String token = login("archive-admin", "Archive-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = create(token, "/fleets", Map.of("fleetCode", suffix, "fleetName", "模拟车队"));
        long a = create(token, "/vehicles", Map.of("plateNo", "A" + suffix, "fleetId", fleet));
        long b = create(token, "/vehicles", Map.of("plateNo", "B" + suffix, "fleetId", fleet));
        long first = create(token, "/terminals", Map.of("terminalNo", terminalNo()));
        long second = create(token, "/terminals", Map.of("terminalNo", terminalNo()));
        change(token, "/vehicles/" + a + "/terminal", Map.of("terminalId", first));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO terminal_vehicle_binding (terminal_id, vehicle_id, effective_from)
                VALUES (?, ?, '2026-11-10 10:00:00')
                """, first, b)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO terminal_vehicle_binding (terminal_id, vehicle_id, effective_from)
                VALUES (?, ?, '2026-11-10 10:00:00')
                """, second, a)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("DELETE FROM terminal WHERE id = ?", first))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("DELETE FROM vehicle WHERE id = ?", a))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private int concurrentBind(java.util.concurrent.CountDownLatch start, String token, long vehicle, long terminal)
            throws Exception {
        start.await();
        return mvc.perform(put("/api/v1/vehicles/" + vehicle + "/terminal").header("satoken", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("terminalId", terminal))))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode change(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(put("/api/v1" + path).header("satoken", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private String rejectedChange(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(put("/api/v1" + path).header("satoken", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsByteArray()).path("code").asText();
    }

    private String terminalNo() {
        return String.format("%020d", sequence.incrementAndGet());
    }

    private static final java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();
    @Autowired com.vms.support.MutableClock clock;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.springframework.boot.test.context.TestConfiguration
    static class TimeConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.vms.support.MutableClock archiveClock() {
            return new com.vms.support.MutableClock(java.time.Instant.parse("2026-11-09T02:00:00Z"),
                    java.time.ZoneId.of("Asia/Shanghai"));
        }
    }

    private String login(String username, String password) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray()).path("token").asText();
    }

    private long create(String token, String path, Object body) throws Exception {
        var response = mvc.perform(post("/api/v1" + path).header("satoken", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray()).path("id").asLong();
    }

    private JsonNode read(String token, String path) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1" + path).header("satoken", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
}
