package com.vms.geofence;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "vms.bootstrap-admin.username=geofence-judge",
        "vms.bootstrap-admin.password=Geofence-Judge-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0"
})
@Import({IntegrationContainers.class, GeofenceJudgementFlowTest.TimeConfiguration.class})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GeofenceJudgementFlowTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TerminalAccessPort access;
    @Autowired MutableClock clock;
    @LocalServerPort int httpPort;

    /** 同一测试上下文共享数据库；每个用例从空围栏表开始。历史报警按车辆过滤，不受影响。 */
    @BeforeEach
    void cleanGeofences() {
        jdbc.update("DELETE FROM geofence_vehicle_state");
        jdbc.update("DELETE FROM geofence_vehicle");
        jdbc.update("DELETE FROM geofence");
    }

    @Test
    @Order(1)
    void firstValidRealtimePointBuildsBaselineAndBoundaryCountsInsideWithMetricDistances() throws Exception {
        String token = login();
        BoundTerminal bound = boundTerminal(token);
        long polygon = createFence(token, polygon("边界多边形",
                List.of(vertex(116.39, 39.89), vertex(116.41, 39.89),
                        vertex(116.41, 39.91), vertex(116.39, 39.91)), List.of(bound.vehicleId())));
        long circle = createFence(token, circle("米制圆", 116.4, 39.9, 500.0, List.of(bound.vehicleId())));
        LocalDateTime start = LocalDateTime.now(clock).withNano(0);

        try (TerminalClient terminal = authenticated(bound)) {
            // 边界上的点首次只建立基线，且边界算内。
            sendAndExpectSuccess(terminal, 1, position(start, 116.4, 39.89));
            assertThat(geofenceAlarmCount(bound)).isZero();
            assertThat(state(polygon, bound).get("inside_state")).isEqualTo("INSIDE");
            assertThat(state(circle, bound).get("inside_state")).isEqualTo("OUTSIDE");

            // 纬度 +0.01 度约 1113 米 > 500 米；按经纬度差比较会误判为圆内。
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 2, position(start.plusSeconds(10), 116.4, 39.91));
            assertThat(state(circle, bound).get("inside_state")).as("半径按米判定").isEqualTo("OUTSIDE");
            assertThat(geofenceAlarmCount(bound)).as("基线建立不产生事件").isZero();

            // 定位无效点不参与围栏计算，不推进基线。
            clock.advance(Duration.ofSeconds(10));
            LocalDateTime beforeInvalid = (LocalDateTime) state(polygon, bound).get("last_point_time");
            sendAndExpectSuccess(terminal, 3, invalidPosition(start.plusSeconds(20), 116.5, 39.9));
            assertThat(state(polygon, bound).get("last_point_time")).isEqualTo(beforeInvalid);
            assertThat(state(circle, bound).get("last_point_time")).isEqualTo(beforeInvalid);
        }
    }

    @Test
    @Order(2)
    void threeConsecutivePointsConfirmCrossingAndProduceOneEventPushedOverWebSocket() throws Exception {
        String token = login();
        BoundTerminal bound = boundTerminal(token);
        long fence = createFence(token, circle("进出圆", 116.4, 39.9, 500.0, List.of(bound.vehicleId())));
        LocalDateTime start = LocalDateTime.now(clock).withNano(0);
        try (WsObserver ws = subscribe(token); TerminalClient terminal = authenticated(bound)) {
            assertThat(ws.open.await(5, TimeUnit.SECONDS)).isTrue();
            sendAndExpectSuccess(terminal, 1, position(start, 116.4, 39.9));
            for (int i = 1; i <= 2; i++) {
                clock.advance(Duration.ofSeconds(10));
                sendAndExpectSuccess(terminal, 2 + i, position(start.plusSeconds(10L * i), 116.5, 39.9));
                assertThat(geofenceAlarmCount(bound)).as("未满 3 点不产生事件").isZero();
            }
            // 第三个越界点坐标在圆外，但未定位。不能确认、清空或推进这两个候选点。
            Map<String, Object> beforeInvalid = state(fence, bound);
            assertThat(((Number) beforeInvalid.get("consecutive_count")).intValue()).isEqualTo(2);
            clock.advance(Duration.ofSeconds(5));
            sendAndExpectSuccess(terminal, 20, invalidPosition(start.plusSeconds(25), 116.5, 39.9));
            assertThat(geofenceAlarmCount(bound)).as("无效点不触发围栏事件").isZero();
            assertThat(state(fence, bound)).isEqualTo(beforeInvalid);

            clock.advance(Duration.ofSeconds(5));
            sendAndExpectSuccess(terminal, 5, position(start.plusSeconds(30), 116.5, 39.9));

            Map<String, Object> event = singleGeofenceAlarm(bound, "GEOFENCE_LEAVE");
            long eventId = ((Number) event.get("id")).longValue();
            assertThat(event.get("condition_state")).isEqualTo("EVENT");
            assertThat(event.get("recovered_at")).isNull();
            assertThat(event.get("source")).isEqualTo("PLATFORM");
            JsonNode snapshot = json.readTree((String) event.get("rule_snapshot"));
            assertThat(snapshot.path("geofenceId").asLong()).isEqualTo(fence);
            assertThat(snapshot.path("fenceVersion").asLong()).isEqualTo(1L);
            assertThat(state(fence, bound).get("inside_state")).isEqualTo("OUTSIDE");
            assertThat(((Number) state(fence, bound).get("consecutive_count")).intValue()).isZero();

            Map<String, Object> outbox = jdbc.queryForMap(
                    "SELECT event_type, aggregate_id, version FROM notification_outbox WHERE event_id = ?",
                    "geofence:leave:" + eventId);
            assertThat(outbox.get("event_type")).isEqualTo("ALARM_EVENT");
            assertThat(((Number) outbox.get("aggregate_id")).longValue()).isEqualTo(eventId);

            JsonNode frame = awaitFrame(ws, "alarm_event", f -> f.path("aggregate_id").asLong() == eventId, 15_000);
            assertThat(frame).as("围栏事件沿用报警 outbox/WS 推送").isNotNull();
            assertThat(frame.path("event_id").asText()).isEqualTo("geofence:leave:" + eventId);
            assertThat(frame.path("payload").path("alarmType").asText()).isEqualTo("GEOFENCE_LEAVE");
            assertThat(frame.path("payload").path("conditionState").asText()).isEqualTo("EVENT");

            // 已在围栏外继续上报不重复产生事件。
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 6, position(start.plusSeconds(40), 116.5, 39.9));
            assertThat(geofenceAlarmCount(bound)).isEqualTo(1);

            // 连续 3 个点回到围栏内，产生一条进入事件。
            for (int i = 1; i <= 3; i++) {
                clock.advance(Duration.ofSeconds(10));
                sendAndExpectSuccess(terminal, 6 + i, position(start.plusSeconds(40L + 10L * i), 116.4, 39.9));
            }
            singleGeofenceAlarm(bound, "GEOFENCE_ENTER");
            assertThat(geofenceAlarmCount(bound)).isEqualTo(2);
            assertThat(state(fence, bound).get("inside_state")).isEqualTo("INSIDE");
        }
    }

    @Test
    @Order(3)
    void disabledEventTypeStillUpdatesBaselineWithoutCreatingThatEvent() throws Exception {
        String token = login();
        BoundTerminal bound = boundTerminal(token);
        long fence = createFence(token, circleWithFlags("仅离开事件", 116.4, 39.9, 500.0,
                List.of(bound.vehicleId()), false, true));
        LocalDateTime start = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 1, position(start, 116.5, 39.9));
            assertThat(state(fence, bound).get("inside_state")).isEqualTo("OUTSIDE");
            for (int i = 1; i <= 3; i++) {
                clock.advance(Duration.ofSeconds(10));
                sendAndExpectSuccess(terminal, 1 + i, position(start.plusSeconds(10L * i), 116.4, 39.9));
            }
            assertThat(geofenceAlarmCount(bound)).as("未勾选进入事件不产生进入事件").isZero();
            assertThat(state(fence, bound).get("inside_state")).isEqualTo("INSIDE");

            for (int i = 1; i <= 3; i++) {
                clock.advance(Duration.ofSeconds(10));
                sendAndExpectSuccess(terminal, 4 + i, position(start.plusSeconds(30L + 10L * i), 116.5, 39.9));
            }
            singleGeofenceAlarm(bound, "GEOFENCE_LEAVE");
        }
    }

    @Test
    @Order(4)
    void jitterLongGapAndHistoryPointsDoNotEmitDuplicateEvents() throws Exception {
        String token = login();
        BoundTerminal bound = boundTerminal(token);
        long fence = createFence(token, circle("去抖圆", 116.4, 39.9, 500.0, List.of(bound.vehicleId())));
        LocalDateTime start = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 1, position(start, 116.4, 39.9));
            int serial = 2;
            for (int i = 1; i <= 6; i++) {
                clock.advance(Duration.ofSeconds(10));
                sendAndExpectSuccess(terminal, serial++, position(start.plusSeconds(10L * i),
                        i % 2 == 1 ? 116.5 : 116.4, 39.9));
            }
            assertThat(geofenceAlarmCount(bound)).as("内外抖动不产生事件").isZero();

            // 第 2 个候选点后间隔 45 秒 > 30 秒，候选重计，不产生事件。
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, serial++, position(start.plusSeconds(70), 116.5, 39.9));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, serial++, position(start.plusSeconds(80), 116.5, 39.9));
            assertThat(geofenceAlarmCount(bound)).isZero();
            clock.advance(Duration.ofSeconds(45));
            sendAndExpectSuccess(terminal, serial++, position(start.plusSeconds(125), 116.5, 39.9));
            assertThat(geofenceAlarmCount(bound)).as("间隔超 30 秒候选重计").isZero();
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, serial++, position(start.plusSeconds(135), 116.5, 39.9));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, serial++, position(start.plusSeconds(145), 116.5, 39.9));
            Map<String, Object> leave = singleGeofenceAlarm(bound, "GEOFENCE_LEAVE");
            LocalDateTime baselineTime = (LocalDateTime) state(fence, bound).get("last_point_time");
            assertThat(baselineTime).isEqualTo(start.plusSeconds(145));

            // 历史补传点（接收时差超过 60 秒）不改变当前围栏状态。
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, serial, position(start.plusSeconds(5), 116.4, 39.9));
            assertThat(jdbc.queryForList("SELECT report_type FROM track_point WHERE vehicle_id = ? AND gps_time = ?",
                    String.class, bound.vehicleId(), start.plusSeconds(5))).containsExactly("HISTORY");
            assertThat(state(fence, bound).get("inside_state")).isEqualTo("OUTSIDE");
            assertThat(state(fence, bound).get("last_point_time")).isEqualTo(baselineTime);
            assertThat(geofenceAlarmCount(bound)).isEqualTo(1);
            assertThat(((Number) leave.get("id")).longValue()).isPositive();
        }
    }

    @Test
    @Order(5)
    void shapeAndBindingChangesRebuildBaselineAuditAndKeepOtherActiveAlarms() throws Exception {
        String token = login();
        BoundTerminal bound = boundTerminal(token);
        long fence = createFence(token, circle("改形圆", 116.4, 39.9, 500.0, List.of(bound.vehicleId())));
        LocalDateTime start = LocalDateTime.now(clock).withNano(0);
        long ruleId = jdbc.queryForObject(
                "SELECT id FROM alarm_rule WHERE alarm_type = 'OVERSPEED' AND scope_type = 'GLOBAL'", Long.class);
        jdbc.update("""
                INSERT INTO alarm (vehicle_id, terminal_id, fleet_id_at_event, source, alarm_type,
                    business_dimension, condition_state, handle_status, rule_id, rule_version,
                    start_time, last_time, created_at, updated_at)
                VALUES (?, ?, ?, 'PLATFORM', 'OVERSPEED', '', 'ACTIVE', 'PENDING', ?, 1, ?, ?, ?, ?)
                """, bound.vehicleId(), bound.terminalId(), bound.fleetId(), ruleId,
                start, start, start, start);
        long activeAlarmId = jdbc.queryForObject(
                "SELECT id FROM alarm WHERE vehicle_id = ? AND alarm_type = 'OVERSPEED'", Long.class,
                bound.vehicleId());
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 1, position(start, 116.4, 39.9));
            assertThat(state(fence, bound).get("inside_state")).isEqualTo("INSIDE");

            // 形状改变：圆心移到 116.5，版本递增；下一个点只重建基线，不推测进出。
            putJson(token, "/geofences/" + fence, circle("改形圆-新位置", 116.5, 39.9, 500.0,
                    List.of(bound.vehicleId())));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 2, position(start.plusSeconds(10), 116.4, 39.9));
            Map<String, Object> rebuilt = state(fence, bound);
            assertThat(((Number) rebuilt.get("fence_version")).longValue()).isEqualTo(2L);
            assertThat(rebuilt.get("inside_state")).isEqualTo("OUTSIDE");
            assertThat(((Number) rebuilt.get("consecutive_count")).intValue()).isZero();
            assertThat(geofenceAlarmCount(bound)).as("改形后首个点只重建基线").isZero();
            assertThat(jdbc.queryForObject("SELECT condition_state FROM alarm WHERE id = ?",
                    String.class, activeAlarmId)).as("不清除其他 ACTIVE 报警").isEqualTo("ACTIVE");

            // 绑定变化：解绑再绑定，版本继续递增；首个实时点重建基线。
            putJson(token, "/geofences/" + fence + "/vehicles", Map.of("vehicleIds", List.of()));
            putJson(token, "/geofences/" + fence + "/vehicles", Map.of("vehicleIds", List.of(bound.vehicleId())));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 3, position(start.plusSeconds(20), 116.5, 39.9));
            Map<String, Object> rebound = state(fence, bound);
            assertThat(((Number) rebound.get("fence_version")).longValue()).isEqualTo(4L);
            assertThat(rebound.get("inside_state")).isEqualTo("INSIDE");
            assertThat(geofenceAlarmCount(bound)).as("绑定变化后首个点不产生事件").isZero();

            List<String> actions = jdbc.queryForList("""
                    SELECT action FROM audit_log WHERE category = 'GEOFENCE' AND target = ? ORDER BY id
                    """, String.class, "geofence:" + fence);
            assertThat(actions).containsSubsequence("UPDATE", "VEHICLES", "VEHICLES")
                    .contains("BASELINE_REBUILD");
        }
    }

    @Test
    @Order(6)
    void eventStateAndOutboxSharePositionTransactionSoFailureRollsBackWithoutAck() throws Exception {
        String token = login();
        BoundTerminal bound = boundTerminal(token);
        long fence = createFence(token, circle("事务圆", 116.4, 39.9, 500.0, List.of(bound.vehicleId())));
        LocalDateTime start = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 1, position(start, 116.4, 39.9));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 2, position(start.plusSeconds(10), 116.5, 39.9));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 3, position(start.plusSeconds(20), 116.5, 39.9));
        }

        jdbc.execute("ALTER TABLE alarm ADD CONSTRAINT injected_geofence_failure CHECK (alarm_type = 'OVERSPEED'"
                + " OR vehicle_id <> " + bound.vehicleId() + ")");
        try (TerminalClient terminal = authenticated(bound)) {
            clock.advance(Duration.ofSeconds(10));
            terminal.send(0x0200, 1, position(start.plusSeconds(30), 116.5, 39.9).encode());
            assertThat(terminal.socket.getInputStream().read()).as("围栏事件写入失败不返回成功 ACK").isEqualTo(-1);
        } finally {
            jdbc.execute("ALTER TABLE alarm DROP CHECK injected_geofence_failure");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE vehicle_id = ?", Long.class,
                bound.vehicleId())).isEqualTo(3);
        assertThat(geofenceAlarmCount(bound)).isZero();
        Map<String, Object> rolledBack = state(fence, bound);
        assertThat(rolledBack.get("inside_state")).isEqualTo("INSIDE");
        assertThat(((Number) rolledBack.get("consecutive_count")).intValue()).isEqualTo(2);
        assertThat(rolledBack.get("last_point_time")).isEqualTo(start.plusSeconds(20));
        assertThat(jdbc.queryForObject("SELECT version FROM notification_outbox WHERE event_id = ?", Long.class,
                "position:" + bound.vehicleId())).isEqualTo(3L);

        try (TerminalClient retry = authenticated(bound)) {
            sendAndExpectSuccess(retry, 1, position(start.plusSeconds(30), 116.5, 39.9));
        }
        singleGeofenceAlarm(bound, "GEOFENCE_LEAVE");
        assertThat(state(fence, bound).get("inside_state")).isEqualTo("OUTSIDE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE vehicle_id = ?", Long.class,
                bound.vehicleId())).isEqualTo(4);
    }

    private Map<String, Object> state(long fenceId, BoundTerminal bound) {
        return jdbc.queryForMap("""
                SELECT fence_version, inside_state, candidate_state, consecutive_count, last_point_time
                FROM geofence_vehicle_state WHERE geofence_id = ? AND vehicle_id = ?
                """, fenceId, bound.vehicleId());
    }

    private long geofenceAlarmCount(BoundTerminal bound) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM alarm WHERE vehicle_id = ? AND alarm_type LIKE 'GEOFENCE_%'
                """, Long.class, bound.vehicleId());
    }

    private Map<String, Object> singleGeofenceAlarm(BoundTerminal bound, String alarmType) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM alarm WHERE vehicle_id = ? AND alarm_type = ?
                """, bound.vehicleId(), alarmType);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private long createFence(String token, Map<String, Object> body) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/geofences").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("id").asLong();
    }

    private JsonNode putJson(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(put("/api/v1" + path).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private Map<String, Object> circle(String name, double lng, double lat, double radius,
                                       List<Long> vehicleIds) {
        return circleWithFlags(name, lng, lat, radius, vehicleIds, true, true);
    }

    private Map<String, Object> circleWithFlags(String name, double lng, double lat, double radius,
                                                List<Long> vehicleIds, boolean enter, boolean leave) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fenceName", name + "-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("fenceType", "CIRCLE");
        body.put("centerLng", lng);
        body.put("centerLat", lat);
        body.put("radiusM", radius);
        body.put("enterEnabled", enter);
        body.put("leaveEnabled", leave);
        body.put("vehicleIds", vehicleIds);
        return body;
    }

    private Map<String, Object> polygon(String name, List<Map<String, Object>> vertices, List<Long> vehicleIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fenceName", name + "-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("fenceType", "POLYGON");
        body.put("vertices", vertices);
        body.put("enterEnabled", true);
        body.put("leaveEnabled", true);
        body.put("vehicleIds", vehicleIds);
        return body;
    }

    private Map<String, Object> vertex(double lng, double lat) {
        Map<String, Object> vertex = new LinkedHashMap<>();
        vertex.put("lng", lng);
        vertex.put("lat", lat);
        return vertex;
    }

    private PositionReport position(LocalDateTime time, double longitude, double latitude) {
        return new PositionReport(0, 3, latitude, longitude, 10, 0, 90, time, List.of());
    }

    private PositionReport invalidPosition(LocalDateTime time, double longitude, double latitude) {
        return new PositionReport(0, 1, latitude, longitude, 10, 0, 90, time, List.of());
    }

    private void sendAndExpectSuccess(TerminalClient terminal, int serial, PositionReport report) throws Exception {
        terminal.send(0x0200, serial, report.encode());
        TerminalGeneralResponse response = TerminalGeneralResponse.parse(terminal.receive().body());
        assertThat(response.responseId()).isEqualTo(0x0200);
        assertThat(response.result()).isZero();
    }

    private String login() throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "geofence-judge",
                                "password", "Geofence-Judge-2026"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .path("token").asText();
    }

    private long createResource(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1" + path).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("id").asLong();
    }

    private BoundTerminal boundTerminal(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = createResource(token, "/fleets",
                Map.of("fleetCode", "J" + suffix, "fleetName", "围栏判定车队" + suffix));
        long vehicle = createResource(token, "/vehicles", Map.of("plateNo", "判定" + suffix, "fleetId", fleet));
        String terminalNo = "013800139001" + String.format("%08d", SEQUENCE.getAndIncrement());
        long terminal = createResource(token, "/terminals", Map.of("terminalNo", terminalNo));
        mvc.perform(put("/api/v1/vehicles/" + vehicle + "/terminal").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("terminalId", terminal))))
                .andExpect(status().isOk());
        return new BoundTerminal(terminal, vehicle, fleet, terminalNo);
    }

    private TerminalClient authenticated(BoundTerminal bound) throws Exception {
        TerminalClient client = new TerminalClient(access.port(), bound.terminalNo());
        client.send(0x0100, 1, new TerminalRegister(1, 1, "ABC12", "TESTMODEL", bound.terminalNo(), 2,
                "围栏车辆").encode());
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

    private JsonNode awaitFrame(WsObserver observer, String type, Predicate<JsonNode> match, long timeoutMillis)
            throws Exception {
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

    private record BoundTerminal(long terminalId, long vehicleId, long fleetId, String terminalNo) { }

    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock geofenceClock() {
            return new MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneId.of("Asia/Shanghai"));
        }
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

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }
}