package com.vms.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vms.access.TerminalAccessPort;
import com.vms.protocol.BodyProperties;
import com.vms.protocol.Frame;
import com.vms.protocol.FrameCodec;
import com.vms.protocol.MessageHeader;
import com.vms.protocol.PositionReport;
import com.vms.protocol.TerminalAuthentication;
import com.vms.protocol.TerminalGeneralResponse;
import com.vms.protocol.TerminalRegister;
import com.vms.protocol.TerminalRegisterResponse;
import com.vms.support.IntegrationContainers;
import com.vms.support.MutableClock;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "vms.bootstrap-admin.username=alarm-admin",
        "vms.bootstrap-admin.password=Alarm-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0"
})
@Import({IntegrationContainers.class, OverspeedAlarmFlowTest.TimeConfiguration.class})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OverspeedAlarmFlowTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TerminalAccessPort access;
    @Autowired MutableClock clock;
    @Autowired OverspeedAlarmParticipant participant;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int httpPort;

    @Test
    @Order(1)
    void threeConsecutiveOverspeedPointsCreateSingleActiveAlarmWithSnapshots() throws Exception {
        String token = login("alarm-admin", "Alarm-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        long driverId = createDriver(token, bound.fleetId());
        bindDriver(token, bound.vehicleId(), driverId);
        LocalDateTime first = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 1, position(first, 900, 116.10));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 2, position(first.plusSeconds(10), 900, 116.20));
            assertThat(alarmCount(bound)).as("两点不足三点，不成立报警").isZero();
            Map<String, Object> candidate = candidate(bound);
            assertThat(candidate.get("candidate_direction")).isEqualTo("OVER");
            assertThat(((Number) candidate.get("consecutive_count")).intValue()).isEqualTo(2);

            clock.advance(Duration.ofSeconds(10));
            LocalDateTime triggeredAt = first.plusSeconds(20);
            sendAndExpectSuccess(terminal, 3, position(triggeredAt, 900, 116.30));

            assertThat(alarmCount(bound)).isEqualTo(1);
            Map<String, Object> alarm = jdbc.queryForMap("SELECT * FROM alarm WHERE vehicle_id = ?",
                    bound.vehicleId());
            assertThat(alarm.get("condition_state")).isEqualTo("ACTIVE");
            assertThat(alarm.get("handle_status")).isEqualTo("PENDING");
            assertThat(alarm.get("source")).isEqualTo("PLATFORM");
            assertThat(alarm.get("alarm_type")).isEqualTo("OVERSPEED");
            assertThat(((Number) alarm.get("terminal_id")).longValue()).isEqualTo(bound.terminalId());
            assertThat(((Number) alarm.get("driver_id")).longValue()).isEqualTo(driverId);
            assertThat(((Number) alarm.get("fleet_id_at_event")).longValue()).isEqualTo(bound.fleetId());
            assertThat(alarm.get("start_time")).isEqualTo(triggeredAt);
            assertThat(alarm.get("last_time")).isEqualTo(triggeredAt);
            assertThat(alarm.get("recovered_at")).isNull();

            long globalRuleId = jdbc.queryForObject(
                    "SELECT id FROM alarm_rule WHERE scope_type = 'GLOBAL' AND alarm_type = 'OVERSPEED'",
                    Long.class);
            assertThat(((Number) alarm.get("rule_id")).longValue()).isEqualTo(globalRuleId);
            assertThat(((Number) alarm.get("rule_version")).longValue()).isEqualTo(1L);
            JsonNode snapshot = json.readTree((String) alarm.get("rule_snapshot"));
            assertThat(snapshot.path("thresholdKph").asInt()).isEqualTo(80);
            assertThat(snapshot.path("scopeType").asText()).isEqualTo("GLOBAL");
            assertThat(snapshot.path("ruleVersion").asLong()).isEqualTo(1L);

            long alarmId = ((Number) alarm.get("id")).longValue();
            assertThat(jdbc.queryForList("SELECT o.event_id, o.aggregate_id, o.version FROM notification_outbox o"
                    + " JOIN alarm a ON a.id = o.aggregate_id WHERE o.event_type = 'ALARM_EVENT' AND a.vehicle_id = ?",
                    bound.vehicleId())).singleElement().satisfies(row -> {
                        assertThat(row.get("event_id")).isEqualTo("alarm:created:" + alarmId);
                        assertThat(((Number) row.get("aggregate_id")).longValue()).isEqualTo(alarmId);
                        assertThat(((Number) row.get("version")).longValue()).isEqualTo(1L);
                    });

            Map<String, Object> activeCandidate = candidate(bound);
            assertThat(((Number) activeCandidate.get("consecutive_count")).intValue()).isEqualTo(3);
            assertThat(activeCandidate.get("last_point_time")).isEqualTo(triggeredAt);

            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 4, position(triggeredAt.plusSeconds(10), 950, 116.40));
            assertThat(alarmCount(bound)).as("持续超速逐点上报不新增事件").isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT last_time FROM alarm WHERE id = ?", LocalDateTime.class, alarmId))
                    .isEqualTo(triggeredAt.plusSeconds(10));
            assertThat(((Number) candidate(bound).get("consecutive_count")).intValue()).isEqualTo(4);
        }
    }

    @Test
    @Order(2)
    void threeConsecutiveQuietPointsRecoverActiveAndNextCycleCreatesNewEvent() throws Exception {
        String token = login("alarm-admin", "Alarm-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime start = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 1, position(start, 900, 116.10));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 2, position(start.plusSeconds(10), 900, 116.20));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 3, position(start.plusSeconds(20), 900, 116.30));
            long alarmId = jdbc.queryForObject(
                    "SELECT id FROM alarm WHERE vehicle_id = ? AND condition_state = 'ACTIVE'",
                    Long.class, bound.vehicleId());

            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 4, position(start.plusSeconds(30), 600, 116.40));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 5, position(start.plusSeconds(40), 600, 116.50));
            assertThat(jdbc.queryForObject("SELECT condition_state FROM alarm WHERE id = ?", String.class, alarmId))
                    .as("两个低点不足以恢复").isEqualTo("ACTIVE");
            assertThat(((Number) candidate(bound).get("consecutive_count")).intValue()).isEqualTo(2);

            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 6, position(start.plusSeconds(50), 600, 116.60));
            Map<String, Object> recovered = jdbc.queryForMap("SELECT * FROM alarm WHERE id = ?", alarmId);
            assertThat(recovered.get("condition_state")).isEqualTo("RECOVERED");
            assertThat(recovered.get("recovered_at")).isEqualTo(start.plusSeconds(50));
            assertThat(recovered.get("last_time")).as("最后发生时间停留在最后一个超速点")
                    .isEqualTo(start.plusSeconds(20));
            assertThat(jdbc.queryForList("SELECT aggregate_id, version FROM notification_outbox"
                    + " WHERE event_type = 'ALARM_EVENT' AND event_id = ?", "alarm:recovered:" + alarmId))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(((Number) row.get("aggregate_id")).longValue()).isEqualTo(alarmId);
                        assertThat(((Number) row.get("version")).longValue()).isEqualTo(1L);
                    });

            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 7, position(start.plusSeconds(60), 600, 116.70));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox o"
                    + " JOIN alarm a ON a.id = o.aggregate_id"
                    + " WHERE o.event_type = 'ALARM_EVENT' AND a.vehicle_id = ?", Long.class, bound.vehicleId()))
                    .as("恢复只产生一次事件").isEqualTo(2);

            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 8, position(start.plusSeconds(70), 900, 116.80));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 9, position(start.plusSeconds(80), 900, 116.90));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 10, position(start.plusSeconds(90), 900, 117.00));

            List<Map<String, Object>> alarms = jdbc.queryForList(
                    "SELECT * FROM alarm WHERE vehicle_id = ? ORDER BY id", bound.vehicleId());
            assertThat(alarms).hasSize(2);
            assertThat(alarms.getFirst().get("condition_state")).isEqualTo("RECOVERED");
            Map<String, Object> next = alarms.getLast();
            assertThat(next.get("condition_state")).isEqualTo("ACTIVE");
            assertThat(next.get("start_time")).isEqualTo(start.plusSeconds(90));
            long nextId = ((Number) next.get("id")).longValue();
            assertThat(nextId).isNotEqualTo(alarmId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE event_id = ?",
                    Long.class, "alarm:created:" + nextId)).isEqualTo(1);
        }
    }

    @Test
    @Order(3)
    void candidateRecountsAfterGapBeyondThirtySecondsButActiveAlarmStays() throws Exception {
        String token = login("alarm-admin", "Alarm-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime start = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 1, position(start, 900, 116.10));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 2, position(start.plusSeconds(10), 900, 116.20));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 3, position(start.plusSeconds(20), 900, 116.30));
            long alarmId = jdbc.queryForObject(
                    "SELECT id FROM alarm WHERE vehicle_id = ? AND condition_state = 'ACTIVE'",
                    Long.class, bound.vehicleId());

            clock.advance(Duration.ofSeconds(30));
            sendAndExpectSuccess(terminal, 4, position(start.plusSeconds(50), 910, 116.40));
            assertThat(((Number) candidate(bound).get("consecutive_count")).intValue())
                    .as("间隔恰为 30 秒仍连续").isEqualTo(4);

            clock.advance(Duration.ofSeconds(31));
            sendAndExpectSuccess(terminal, 5, position(start.plusSeconds(81), 920, 116.50));
            Map<String, Object> afterGap = candidate(bound);
            assertThat(((Number) afterGap.get("consecutive_count")).intValue())
                    .as("间隔超过 30 秒重新计数").isEqualTo(1);
            assertThat(afterGap.get("candidate_direction")).isEqualTo("OVER");
            Map<String, Object> alarm = jdbc.queryForMap("SELECT * FROM alarm WHERE id = ?", alarmId);
            assertThat(alarm.get("condition_state")).as("候选重计不清除 ACTIVE").isEqualTo("ACTIVE");
            assertThat(alarm.get("recovered_at")).isNull();
            assertThat(alarm.get("last_time")).as("最后发生时间继续推进").isEqualTo(start.plusSeconds(81));

            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 6, position(start.plusSeconds(91), 930, 116.60));
            assertThat(((Number) candidate(bound).get("consecutive_count")).intValue()).isEqualTo(2);
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 7, position(start.plusSeconds(101), 940, 116.70));
            assertThat(((Number) candidate(bound).get("consecutive_count")).intValue()).isEqualTo(3);
            assertThat(alarmCount(bound)).as("重计后确认不新建事件").isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT last_time FROM alarm WHERE id = ?", LocalDateTime.class, alarmId))
                    .isEqualTo(start.plusSeconds(101));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox o"
                    + " JOIN alarm a ON a.id = o.aggregate_id"
                    + " WHERE o.event_type = 'ALARM_EVENT' AND a.vehicle_id = ?", Long.class, bound.vehicleId()))
                    .isEqualTo(1);
        }
    }

    @Test
    @Order(4)
    void invalidFixPointsDoNotAdvanceConfirmationOrRecovery() throws Exception {
        String token = login("alarm-admin", "Alarm-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime start = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 1, position(start, 900, 116.10));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 2, position(start.plusSeconds(10), 900, 116.20));
            clock.advance(Duration.ofSeconds(5));
            sendAndExpectSuccess(terminal, 3, invalidPosition(start.plusSeconds(15), 900, 116.30));
            assertThat(((Number) candidate(bound).get("consecutive_count")).intValue())
                    .as("无效定位不推进成立候选").isEqualTo(2);

            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 4, position(start.plusSeconds(25), 900, 116.40));
            assertThat(alarmCount(bound)).as("第三个有效超速点才成立").isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT start_time FROM alarm WHERE vehicle_id = ?",
                    LocalDateTime.class, bound.vehicleId())).isEqualTo(start.plusSeconds(25));

            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 5, position(start.plusSeconds(35), 600, 116.50));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 6, position(start.plusSeconds(45), 600, 116.60));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 7, invalidPosition(start.plusSeconds(55), 600, 116.70));
            assertThat(jdbc.queryForObject("SELECT condition_state FROM alarm WHERE vehicle_id = ?",
                    String.class, bound.vehicleId())).as("无效点不推进恢复").isEqualTo("ACTIVE");
            assertThat(((Number) candidate(bound).get("consecutive_count")).intValue()).isEqualTo(2);

            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 8, position(start.plusSeconds(65), 600, 116.80));
            assertThat(jdbc.queryForObject("SELECT condition_state FROM alarm WHERE vehicle_id = ?",
                    String.class, bound.vehicleId())).isEqualTo("RECOVERED");
        }
    }

    @Test
    @Order(10)
    void adminMaintainsGlobalAndFleetRulesWithVersionsValidationAuditAndRoleCheck() throws Exception {
        String token = login("alarm-admin", "Alarm-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        JsonNode initial = getJson(token, "/admin/alarm-rules");
        assertThat(initial.path("global").path("alarmType").asText()).isEqualTo("OVERSPEED");
        assertThat(initial.path("global").path("scopeType").asText()).isEqualTo("GLOBAL");
        assertThat(initial.path("global").path("thresholdKph").asInt()).isEqualTo(80);
        assertThat(initial.path("fleetOverrides").size()).isZero();
        long initialVersion = initial.path("global").path("version").asLong();

        JsonNode updated = putJson(token, "/admin/alarm-rules/global", Map.of("thresholdKph", 95));
        assertThat(updated.path("thresholdKph").asInt()).isEqualTo(95);
        assertThat(updated.path("version").asLong()).isEqualTo(initialVersion + 1);

        JsonNode override = putJson(token, "/admin/alarm-rules/fleets/" + bound.fleetId(),
                Map.of("thresholdKph", 60));
        assertThat(override.path("scopeType").asText()).isEqualTo("FLEET");
        assertThat(override.path("fleetId").asLong()).isEqualTo(bound.fleetId());
        assertThat(override.path("thresholdKph").asInt()).isEqualTo(60);
        assertThat(override.path("version").asLong()).isEqualTo(1);
        JsonNode overview = getJson(token, "/admin/alarm-rules");
        assertThat(overview.path("fleetOverrides").size()).isEqualTo(1);
        assertThat(overview.path("fleetOverrides").get(0).path("fleetId").asLong()).isEqualTo(bound.fleetId());

        JsonNode changed = putJson(token, "/admin/alarm-rules/fleets/" + bound.fleetId(),
                Map.of("thresholdKph", 65));
        assertThat(changed.path("thresholdKph").asInt()).isEqualTo(65);
        assertThat(changed.path("version").asLong()).isEqualTo(2);

        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE category = 'ALARM'", String.class))
                .contains("UPDATE_GLOBAL", "UPSERT_FLEET_OVERRIDE");
        assertThat(jdbc.queryForObject("SELECT detail FROM audit_log WHERE category = 'ALARM'"
                + " AND action = 'UPDATE_GLOBAL'", String.class)).contains("thresholdKph=95");

        int sequence = SEQUENCE.getAndIncrement();
        create(token, "/users", Map.of("username", "rule-monitor-" + sequence,
                "password", "Monitor-Pass-2026", "roleCode", "MONITOR", "fleetIds", List.of()));
        String monitor = login("rule-monitor-" + sequence, "Monitor-Pass-2026");
        mvc.perform(get("/api/v1/admin/alarm-rules").header("satoken", monitor))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/alarm-rules/global").header("satoken", monitor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"thresholdKph\":90}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/alarm-rules/global").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"thresholdKph\":0}"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/v1/admin/alarm-rules/fleets/" + bound.fleetId()).header("satoken", token))
                .andExpect(status().isNoContent());
        assertThat(getJson(token, "/admin/alarm-rules").path("fleetOverrides").size()).isZero();
    }

    @Test
    @Order(11)
    void fleetOverrideWinsAndRuleChangesOnlyAffectLaterPositions() throws Exception {
        String token = login("alarm-admin", "Alarm-Pass-2026");
        BoundTerminal fleetA = boundTerminal(token);
        BoundTerminal fleetB = boundTerminal(token);
        JsonNode global = putJson(token, "/admin/alarm-rules/global", Map.of("thresholdKph", 50));
        long globalVersion = global.path("version").asLong();
        putJson(token, "/admin/alarm-rules/fleets/" + fleetA.fleetId(), Map.of("thresholdKph", 90));

        LocalDateTime start = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(fleetA)) {
            for (int i = 1; i <= 3; i++) {
                if (i > 1) {
                    clock.advance(Duration.ofSeconds(10));
                }
                sendAndExpectSuccess(terminal, i, position(start.plusSeconds((i - 1) * 10L), 700,
                        116.10 + i / 10.0));
            }
        }
        assertThat(alarmCount(fleetA)).as("车队覆盖 90 优先于全局 50，70 km/h 不成立").isZero();

        LocalDateTime startB = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(fleetB)) {
            for (int i = 1; i <= 3; i++) {
                if (i > 1) {
                    clock.advance(Duration.ofSeconds(10));
                }
                sendAndExpectSuccess(terminal, i, position(startB.plusSeconds((i - 1) * 10L), 700,
                        117.10 + i / 10.0));
            }
        }
        Map<String, Object> globalAlarm = jdbc.queryForMap(
                "SELECT * FROM alarm WHERE vehicle_id = ? AND condition_state = 'ACTIVE'", fleetB.vehicleId());
        assertThat(((Number) globalAlarm.get("rule_version")).longValue()).isEqualTo(globalVersion);
        JsonNode globalSnapshot = json.readTree((String) globalAlarm.get("rule_snapshot"));
        assertThat(globalSnapshot.path("scopeType").asText()).isEqualTo("GLOBAL");
        assertThat(globalSnapshot.path("thresholdKph").asInt()).isEqualTo(50);

        JsonNode override = putJson(token, "/admin/alarm-rules/fleets/" + fleetA.fleetId(),
                Map.of("thresholdKph", 60));
        assertThat(override.path("version").asLong()).isEqualTo(2);
        LocalDateTime startA = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(fleetA)) {
            for (int i = 4; i <= 9; i++) {
                if (i > 4) {
                    clock.advance(Duration.ofSeconds(10));
                }
                sendAndExpectSuccess(terminal, i,
                        position(startA.plusSeconds((i - 4) * 10L), 700, 116.10 + i / 10.0));
            }
        }
        Map<String, Object> fleetAlarm = jdbc.queryForMap(
                "SELECT * FROM alarm WHERE vehicle_id = ? AND condition_state = 'ACTIVE'", fleetA.vehicleId());
        assertThat(((Number) fleetAlarm.get("rule_version")).longValue()).isEqualTo(2L);
        JsonNode fleetSnapshot = json.readTree((String) fleetAlarm.get("rule_snapshot"));
        assertThat(fleetSnapshot.path("scopeType").asText()).isEqualTo("FLEET");
        assertThat(fleetSnapshot.path("fleetId").asLong()).isEqualTo(fleetA.fleetId());
        assertThat(fleetSnapshot.path("thresholdKph").asInt()).isEqualTo(60);

        putJson(token, "/admin/alarm-rules/global", Map.of("thresholdKph", 55));
        Map<String, Object> untouched = jdbc.queryForMap("SELECT * FROM alarm WHERE id = ?",
                ((Number) globalAlarm.get("id")).longValue());
        assertThat(((Number) untouched.get("rule_version")).longValue()).isEqualTo(globalVersion);
        assertThat(json.readTree((String) untouched.get("rule_snapshot")).path("thresholdKph").asInt())
                .as("规则修改不追溯既有事件").isEqualTo(50);
    }

    @Test
    @Order(5)
    void newAlarmIsPushedToAuthorizedSubscribersOnly() throws Exception {
        String token = login("alarm-admin", "Alarm-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        BoundTerminal otherFleet = boundTerminal(token);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String leaderName = "alarm-ws-" + suffix;
        create(token, "/users", Map.of("username", leaderName, "password", "Leader-Pass-2026",
                "roleCode", "FLEET_LEADER", "fleetIds", List.of(otherFleet.fleetId())));
        String leaderToken = login(leaderName, "Leader-Pass-2026");

        try (WsObserver adminWs = subscribe(token); WsObserver leaderWs = subscribe(leaderToken)) {
            assertThat(adminWs.open.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(leaderWs.open.await(5, TimeUnit.SECONDS)).isTrue();

            LocalDateTime start = LocalDateTime.now(clock).withNano(0);
            try (TerminalClient terminal = authenticated(bound)) {
                for (int i = 1; i <= 3; i++) {
                    if (i > 1) {
                        clock.advance(Duration.ofSeconds(10));
                    }
                    sendAndExpectSuccess(terminal, i,
                            position(start.plusSeconds((i - 1) * 10L), 900, 116.10 + i / 10.0));
                }
            }
            long alarmId = jdbc.queryForObject(
                    "SELECT id FROM alarm WHERE vehicle_id = ? AND condition_state = 'ACTIVE'",
                    Long.class, bound.vehicleId());

            JsonNode alarmFrame = null;
            JsonNode positionFrame = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while ((alarmFrame == null || positionFrame == null) && System.nanoTime() < deadline) {
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                String text = adminWs.messages.poll(Math.max(1, remaining), TimeUnit.MILLISECONDS);
                if (text == null) {
                    break;
                }
                JsonNode frame = json.readTree(text);
                if ("alarm_event".equals(frame.path("type").asText())
                        && frame.path("aggregate_id").asLong() == alarmId) {
                    alarmFrame = frame;
                }
                if ("vehicle_position".equals(frame.path("type").asText())
                        && frame.path("aggregate_id").asLong() == bound.vehicleId()) {
                    positionFrame = frame;
                }
            }
            assertThat(positionFrame).as("位置推送不回归").isNotNull();
            assertThat(alarmFrame).as("新报警提交后经 outbox 分发推送给订阅者").isNotNull();
            assertThat(alarmFrame.path("event_id").asText()).isEqualTo("alarm:created:" + alarmId);
            assertThat(alarmFrame.path("version").asLong()).isEqualTo(1L);
            assertThat(alarmFrame.path("server_time").asText()).isNotBlank();
            JsonNode payload = alarmFrame.path("payload");
            assertThat(payload.path("alarmId").asLong()).isEqualTo(alarmId);
            assertThat(payload.path("vehicleId").asLong()).isEqualTo(bound.vehicleId());
            assertThat(payload.path("fleetId").asLong()).isEqualTo(bound.fleetId());
            assertThat(payload.path("alarmType").asText()).isEqualTo("OVERSPEED");
            assertThat(payload.path("source").asText()).isEqualTo("PLATFORM");
            assertThat(payload.path("conditionState").asText()).isEqualTo("ACTIVE");
            assertThat(payload.path("ruleVersion").asLong()).isEqualTo(1L);

            assertThat(awaitFrame(leaderWs, "alarm_event", frame -> true, 3_500))
                    .as("其他车队订阅者收不到越界报警").isNull();
        }
    }

    @Test
    @Order(6)
    void alarmWriteJoinsPositionTransactionSoFailuresRollBackWithoutAckAndCommitPrecedesAck() throws Exception {
        String token = login("alarm-admin", "Alarm-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime start = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 1, position(start, 900, 116.10));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 2, position(start.plusSeconds(10), 900, 116.20));
        }

        // 约束只作用于本测试车辆，避免同上下文其他测试遗留的 OVERSPEED 行导致 ALTER 校验失败
        jdbc.execute("ALTER TABLE alarm ADD CONSTRAINT injected_alarm_failure CHECK (vehicle_id <> "
                + bound.vehicleId() + ")");
        try (TerminalClient terminal = authenticated(bound)) {
            clock.advance(Duration.ofSeconds(10));
            terminal.send(0x0200, 1, position(start.plusSeconds(20), 900, 116.30).encode());
            assertThat(terminal.socket.getInputStream().read()).as("报警写入失败不返回成功 ACK").isEqualTo(-1);
        } finally {
            jdbc.execute("ALTER TABLE alarm DROP CHECK injected_alarm_failure");
        }
        assertThat(alarmCount(bound)).as("报警与位置同事务回滚").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE vehicle_id = ?", Long.class,
                bound.vehicleId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT version FROM vehicle_last_position WHERE vehicle_id = ?",
                Long.class, bound.vehicleId())).isEqualTo(2L);
        Map<String, Object> rolledBack = candidate(bound);
        assertThat(((Number) rolledBack.get("consecutive_count")).intValue()).isEqualTo(2);
        assertThat(rolledBack.get("last_point_time")).isEqualTo(start.plusSeconds(10));
        assertThat(jdbc.queryForObject("SELECT version FROM notification_outbox WHERE event_id = ?", Long.class,
                "position:" + bound.vehicleId())).as("位置 outbox 未被失败事务推进").isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox o"
                + " JOIN alarm a ON a.id = o.aggregate_id"
                + " WHERE o.event_type = 'ALARM_EVENT' AND a.vehicle_id = ?", Long.class, bound.vehicleId())).isZero();

        try (TerminalClient retry = authenticated(bound);
             java.sql.Connection blocker = jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT id FROM terminal WHERE id=? FOR UPDATE")) {
                lock.setLong(1, bound.terminalId());
                lock.executeQuery().close();
            }
            retry.send(0x0200, 1, position(start.plusSeconds(20), 900, 116.30).encode());
            retry.socket.setSoTimeout(350);
            assertThatThrownBy(() -> retry.socket.getInputStream().read())
                    .as("提交前不返回 ACK").isInstanceOf(SocketTimeoutException.class);
            assertThat(alarmCount(bound)).as("未提交的报警对其他连接不可见").isZero();
            blocker.rollback();
            retry.socket.setSoTimeout(5000);
            assertThat(TerminalGeneralResponse.parse(retry.receive().body()).result()).isZero();
        }
        assertThat(alarmCount(bound)).as("提交后才可见报警").isEqualTo(1);
        assertThat(((Number) candidate(bound).get("consecutive_count")).intValue()).isEqualTo(3);
    }

    @Test
    @Order(7)
    void concurrentConfirmationKeepsOneActiveAlarmAndDatabaseConstraintRejectsDuplicate() throws Exception {
        String token = login("alarm-admin", "Alarm-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime start = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 1, position(start, 900, 116.10));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 2, position(start.plusSeconds(10), 900, 116.20));
        }
        assertThat(((Number) candidate(bound).get("consecutive_count")).intValue())
                .as("并发测试前候选为 2").isEqualTo(2);

        TransactionTemplate template = new TransactionTemplate(transactions);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> template.executeWithoutResult(status ->
                    participant.apply(bound.vehicleId(), bound.terminalId(), bound.fleetId(), 10,
                            position(start.plusSeconds(20), 900, 116.30), start.plusSeconds(25))));
            Future<?> second = pool.submit(() -> template.executeWithoutResult(status ->
                    participant.apply(bound.vehicleId(), bound.terminalId(), bound.fleetId(), 11,
                            position(start.plusSeconds(30), 910, 116.40), start.plusSeconds(35))));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForList("SELECT id FROM alarm WHERE vehicle_id = ? AND condition_state = 'ACTIVE'",
                Long.class, bound.vehicleId())).as("并发确认只留一个 ACTIVE").hasSize(1);
        assertThat(candidate(bound).get("candidate_direction")).isEqualTo("OVER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE event_type = 'ALARM_EVENT'"
                + " AND aggregate_id IN (SELECT id FROM alarm WHERE vehicle_id = ?)", Long.class, bound.vehicleId()))
                .as("并发确认只产生一次创建事件").isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'alarm'
                  AND index_name = 'uk_alarm_active_dimension' AND non_unique = 0
                """, Integer.class)).as("ACTIVE 唯一维度由数据库唯一约束保障").isEqualTo(1);
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO alarm (vehicle_id, terminal_id, fleet_id_at_event, source, alarm_type,
                    business_dimension, condition_state, handle_status, start_time, last_time, created_at, updated_at)
                VALUES (?, ?, ?, 'PLATFORM', 'OVERSPEED', '', 'ACTIVE', 'PENDING', ?, ?, ?, ?)
                """, bound.vehicleId(), bound.terminalId(), bound.fleetId(), now, now, now, now))
                .as("同维度第二个 ACTIVE 被唯一约束拒绝")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_alarm_active_dimension");

        jdbc.update("DELETE FROM notification_outbox WHERE event_type = 'ALARM_EVENT'"
                + " AND aggregate_id IN (SELECT id FROM alarm WHERE vehicle_id = ?)", bound.vehicleId());
        jdbc.update("DELETE FROM alarm WHERE vehicle_id = ?", bound.vehicleId());
        jdbc.update("DELETE FROM alarm_candidate_state WHERE vehicle_id = ?", bound.vehicleId());
    }

    @Test
    @Order(8)
    void activeAlarmSurvivesCandidateRebuildAndOnlyRecoversAfterThreeQuietPoints() throws Exception {
        String token = login("alarm-admin", "Alarm-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime start = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 1, position(start, 900, 116.10));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 2, position(start.plusSeconds(10), 900, 116.20));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 3, position(start.plusSeconds(20), 900, 116.30));
            long alarmId = jdbc.queryForObject(
                    "SELECT id FROM alarm WHERE vehicle_id = ? AND condition_state = 'ACTIVE'",
                    Long.class, bound.vehicleId());

            // 模拟重启后候选状态重建：ACTIVE 保留，停机期间不补算，后续点从 1 重新累计。
            jdbc.update("DELETE FROM alarm_candidate_state WHERE vehicle_id = ?", bound.vehicleId());
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 4, position(start.plusSeconds(30), 900, 116.40));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 5, position(start.plusSeconds(40), 900, 116.50));
            assertThat(((Number) candidate(bound).get("consecutive_count")).intValue()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT condition_state FROM alarm WHERE id = ?", String.class, alarmId))
                    .as("重启不自动恢复 ACTIVE").isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT last_time FROM alarm WHERE id = ?", LocalDateTime.class, alarmId))
                    .isEqualTo(start.plusSeconds(40));
            assertThat(alarmCount(bound)).as("候选重建后确认不新建事件").isEqualTo(1);

            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 6, position(start.plusSeconds(50), 600, 116.60));
            assertThat(jdbc.queryForObject("SELECT condition_state FROM alarm WHERE id = ?", String.class, alarmId))
                    .as("单个低点不足以恢复").isEqualTo("ACTIVE");
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 7, position(start.plusSeconds(60), 600, 116.70));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 8, position(start.plusSeconds(70), 600, 116.80));
            Map<String, Object> recovered = jdbc.queryForMap("SELECT * FROM alarm WHERE id = ?", alarmId);
            assertThat(recovered.get("condition_state")).isEqualTo("RECOVERED");
            assertThat(recovered.get("recovered_at")).isEqualTo(start.plusSeconds(70));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox"
                    + " WHERE event_type = 'ALARM_EVENT' AND event_id = ?", Long.class,
                    "alarm:recovered:" + alarmId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox"
                    + " WHERE event_type = 'ALARM_EVENT' AND event_id = ?", Long.class,
                    "alarm:created:" + alarmId)).isEqualTo(1);
        }
    }

    private JsonNode getJson(String token, String path) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1" + path).header("satoken", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private JsonNode putJson(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(put("/api/v1" + path).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private Map<String, Object> candidate(BoundTerminal bound) {
        return jdbc.queryForMap("""
                SELECT candidate_direction, consecutive_count, last_point_time, rule_id, rule_version
                FROM alarm_candidate_state
                WHERE vehicle_id = ? AND alarm_type = 'OVERSPEED' AND source = 'PLATFORM' AND business_dimension = ''
                """, bound.vehicleId());
    }

    private long alarmCount(BoundTerminal bound) {
        return jdbc.queryForObject("SELECT count(*) FROM alarm WHERE vehicle_id = ?", Long.class, bound.vehicleId());
    }

    private void sendAndExpectSuccess(TerminalClient terminal, int serial, PositionReport report) throws Exception {
        terminal.send(0x0200, serial, report.encode());
        TerminalGeneralResponse response = TerminalGeneralResponse.parse(terminal.receive().body());
        assertThat(response.responseId()).isEqualTo(0x0200);
        assertThat(response.responseSerial()).isEqualTo(serial);
        assertThat(response.result()).isZero();
    }

    private PositionReport position(LocalDateTime time, int speed, double longitude) {
        return new PositionReport(0, 3, 39.908, longitude, 10, speed, 90, time, List.of());
    }

    /** 状态位 bit1 为 0 表示定位无效，不参与实时状态与报警判定。 */
    private PositionReport invalidPosition(LocalDateTime time, int speed, double longitude) {
        return new PositionReport(0, 1, 39.908, longitude, 10, speed, 90, time, List.of());
    }

    private long createDriver(String token, long fleetId) throws Exception {
        int sequence = SEQUENCE.getAndIncrement();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return create(token, "/drivers", Map.of("driverNo", "D" + suffix, "name", "司机" + sequence,
                "phone", String.format("138%08d", sequence), "fleetId", fleetId));
    }

    private void bindDriver(String token, long vehicleId, long driverId) throws Exception {
        mvc.perform(put("/api/v1/vehicles/" + vehicleId + "/driver").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("driverId", driverId))))
                .andExpect(status().isOk());
    }

    private BoundTerminal boundTerminal(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String terminalNo = "013800139000" + String.format("%08d", SEQUENCE.getAndIncrement());
        long fleet = create(token, "/fleets", Map.of("fleetCode", "A" + suffix, "fleetName", "报警车队" + suffix));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", "报警" + suffix, "fleetId", fleet));
        long terminal = create(token, "/terminals", Map.of("terminalNo", terminalNo));
        mvc.perform(put("/api/v1/vehicles/" + vehicle + "/terminal").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("terminalId", terminal))))
                .andExpect(status().isOk());
        return new BoundTerminal(terminal, vehicle, fleet, terminalNo);
    }

    private String login(String username, String password) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .path("token").asText();
    }

    private long create(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1" + path).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("id").asLong();
    }

    private TerminalClient authenticated(BoundTerminal bound) throws Exception {
        TerminalClient client = new TerminalClient(access.port(), bound.terminalNo());
        client.send(0x0100, 1, new TerminalRegister(1, 1, "ABC12", "TESTMODEL", bound.terminalNo(), 2, "报警车辆").encode());
        TerminalRegisterResponse registration = TerminalRegisterResponse.parse(client.receive().body());
        assertThat(registration.result()).isZero();
        client.send(0x0102, 2, new TerminalAuthentication(registration.authCode(), "012345678901234",
                "00000000000000000001").encode());
        assertThat(TerminalGeneralResponse.parse(client.receive().body()).result()).isZero();
        return client;
    }

    private WsObserver subscribe(String token) throws Exception {
        String ticket = json.readTree(mvc.perform(post("/api/v1/auth/ws-ticket").header("satoken", token))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .path("ticket").asText();
        WsObserver observer = new WsObserver();
        observer.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .buildAsync(URI.create("ws://127.0.0.1:" + httpPort + "/ws/monitor?ticket=" + ticket), observer)
                .get(5, TimeUnit.SECONDS);
        return observer;
    }

    private JsonNode awaitFrame(WsObserver observer, String type, Predicate<JsonNode> match,
                                long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            String text = observer.messages.poll(Math.max(1, remaining), TimeUnit.MILLISECONDS);
            if (text == null) {
                break;
            }
            JsonNode frame = json.readTree(text);
            if (type.equals(frame.path("type").asText()) && match.test(frame)) {
                return frame;
            }
        }
        return null;
    }

    private static final class WsObserver implements WebSocket.Listener, AutoCloseable {
        final CountDownLatch open = new CountDownLatch(1);
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        volatile WebSocket socket;

        @Override public void onOpen(WebSocket ws) {
            socket = ws;
            open.countDown();
            ws.request(1);
        }

        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            messages.add(data.toString());
            ws.request(1);
            return null;
        }

        @Override public void onError(WebSocket ws, Throwable error) { }

        @Override public void close() {
            if (socket != null) {
                socket.abort();
            }
        }
    }

    private record BoundTerminal(long terminalId, long vehicleId, long fleetId, String terminalNo) { }

    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock alarmClock() {
            return new MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneId.of("Asia/Shanghai"));
        }
    }

    private static final class TerminalClient implements AutoCloseable {
        private final Socket socket = new Socket();
        private final String terminalNo;
        TerminalClient(int port, String terminalNo) throws Exception {
            this.terminalNo = terminalNo;
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(5000);
        }
        void send(int messageId, int serial, byte[] body) throws Exception {
            socket.getOutputStream().write(FrameCodec.encode(new Frame(new MessageHeader(messageId,
                    BodyProperties.plain(body.length), 1, terminalNo, serial), body)));
            socket.getOutputStream().flush();
        }
        Frame receive() throws Exception {
            List<Byte> bytes = new ArrayList<>();
            while (bytes.size() < 4096) {
                int value = socket.getInputStream().read();
                if (value < 0) throw new IllegalStateException("TCP closed before ACK");
                if (bytes.isEmpty() && value != 0x7e) continue;
                bytes.add((byte) value);
                if (value == 0x7e && bytes.size() > 1) {
                    byte[] frame = new byte[bytes.size()];
                    for (int i = 0; i < frame.length; i++) frame[i] = bytes.get(i);
                    return FrameCodec.decode(frame);
                }
            }
            throw new IllegalStateException("TCP frame exceeded limit");
        }
        @Override public void close() throws Exception { socket.close(); }
    }
}

