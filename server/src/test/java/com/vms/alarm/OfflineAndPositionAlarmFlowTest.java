package com.vms.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vms.access.TerminalAccessPort;
import com.vms.protocol.BodyProperties;
import com.vms.protocol.Frame;
import com.vms.protocol.FrameCodec;
import com.vms.protocol.MessageHeader;
import com.vms.protocol.PositionBatch;
import com.vms.protocol.PositionReport;
import com.vms.protocol.TerminalAuthentication;
import com.vms.protocol.TerminalGeneralResponse;
import com.vms.protocol.TerminalRegister;
import com.vms.protocol.TerminalRegisterResponse;
import com.vms.support.IntegrationContainers;
import com.vms.support.MutableClock;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 14：离线、定位无效与终端来源报警。
 *
 * <p>位置触发部分走真实 TCP 与 07 位置事务；离线判定由服务内扫描器按有效通信时间推进，
 * 测试直接调用同一 Spring bean 的扫描入口，避免依赖墙钟调度。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "vms.bootstrap-admin.username=alarm14-admin",
        "vms.bootstrap-admin.password=Alarm14-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0",
        // 测试手工推进扫描；关闭墙钟调度，避免与 MutableClock 竞争
        "vms.alarm.offline-scan-interval-ms=3600000",
        "vms.alarm.offline-scan-initial-delay-ms=3600000"
})
@Import({IntegrationContainers.class, OfflineAndPositionAlarmFlowTest.TimeConfiguration.class})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OfflineAndPositionAlarmFlowTest {

    private static final String ADMIN = "alarm14-admin";
    private static final String PASSWORD = "Alarm14-Pass-2026";
    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TerminalAccessPort access;
    @Autowired OfflineAlarmService offlineAlarms;
    @Autowired MutableClock clock;
    @org.springframework.boot.test.web.server.LocalServerPort int httpPort;

    /** AC14：连续 3 个实时无效点成立，3 个有效点恢复；无效点坐标 NULL 且不进地图与超速。 */
    @Test
    @Order(1)
    void threeInvalidRealtimePointsCreatePositionInvalidAlarmAndThreeValidPointsRecover() throws Exception {
        String token = login();
        BoundTerminal bound = boundTerminal(token);
        long driverId = createDriver(token, bound.fleetId());
        bindDriver(token, bound.vehicleId(), driverId);
        LocalDateTime start = LocalDateTime.now(clock).withNano(0);
        LocalDateTime triggeredAt = start.plusSeconds(20);

        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 1, invalidPosition(start, 900, 116.10));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 2, invalidPosition(start.plusSeconds(10), 900, 116.20));
            assertThat(alarmCount(bound)).as("两个无效点不足三个，不成立报警").isZero();
            Map<String, Object> candidate = candidate(bound, "POSITION_INVALID");
            assertThat(candidate.get("candidate_direction")).isEqualTo("INVALID");
            assertThat(((Number) candidate.get("consecutive_count")).intValue()).isEqualTo(2);
            assertThat(candidate.get("rule_id")).as("定位无效不依赖规则").isNull();
            assertThat(candidate.get("rule_version")).isNull();
            assertThat(candidateCount(bound, "OVERSPEED"))
                    .as("无效点速度再高也不进入超速候选").isZero();

            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 3, invalidPosition(triggeredAt, 900, 116.30));
        }

        Map<String, Object> alarm = jdbc.queryForMap(
                "SELECT * FROM alarm WHERE vehicle_id = ? AND alarm_type = 'POSITION_INVALID'", bound.vehicleId());
        assertThat(alarm.get("condition_state")).isEqualTo("ACTIVE");
        assertThat(alarm.get("handle_status")).isEqualTo("PENDING");
        assertThat(alarm.get("source")).isEqualTo("PLATFORM");
        assertThat(((Number) alarm.get("terminal_id")).longValue()).isEqualTo(bound.terminalId());
        assertThat(((Number) alarm.get("driver_id")).longValue()).isEqualTo(driverId);
        assertThat(((Number) alarm.get("fleet_id_at_event")).longValue()).isEqualTo(bound.fleetId());
        assertThat(alarm.get("rule_id")).as("定位无效无报警规则").isNull();
        assertThat(alarm.get("rule_version")).isNull();
        assertThat(alarm.get("rule_snapshot")).isNull();
        assertThat(alarm.get("start_time")).isEqualTo(triggeredAt);
        assertThat(alarm.get("last_time")).isEqualTo(triggeredAt);
        assertThat(alarm.get("recovered_at")).isNull();
        long alarmId = ((Number) alarm.get("id")).longValue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox"
                + " WHERE event_type = 'ALARM_EVENT' AND event_id = ?", Long.class,
                "alarm:created:" + alarmId)).isEqualTo(1);

        List<Map<String, Object>> points = jdbc.queryForList(
                "SELECT lng, lat, locate_state, report_type, raw_body FROM track_point"
                        + " WHERE vehicle_id = ? ORDER BY gps_time", bound.vehicleId());
        assertThat(points).hasSize(3);
        for (Map<String, Object> point : points) {
            assertThat(point.get("locate_state")).isEqualTo("INVALID");
            assertThat(point.get("report_type")).isEqualTo("REALTIME");
            assertThat(point.get("lng")).as("无效点不得伪造 0 或复用旧坐标").isNull();
            assertThat(point.get("lat")).isNull();
            assertThat((byte[]) point.get("raw_body")).as("保留受限原始定位体").isNotEmpty();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_last_position WHERE vehicle_id = ?",
                Long.class, bound.vehicleId())).as("无效点不进入最新有效位置与地图").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_monitor_state WHERE vehicle_id = ?",
                Long.class, bound.vehicleId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE event_id = ?",
                Long.class, "position:" + bound.vehicleId())).isZero();
        assertThat(alarmCount(bound)).isEqualTo(1);

        LocalDateTime next = triggeredAt.plusSeconds(10);
        try (TerminalClient terminal = authenticated(bound)) {
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 1, position(next, 600, 117.10));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 2, position(next.plusSeconds(10), 600, 117.20));
            assertThat(jdbc.queryForObject("SELECT condition_state FROM alarm WHERE id = ?", String.class, alarmId))
                    .as("两个有效点不足以恢复").isEqualTo("ACTIVE");
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 3, position(next.plusSeconds(20), 600, 117.30));
        }

        Map<String, Object> recovered = jdbc.queryForMap("SELECT * FROM alarm WHERE id = ?", alarmId);
        assertThat(recovered.get("condition_state")).isEqualTo("RECOVERED");
        assertThat(recovered.get("recovered_at")).isEqualTo(next.plusSeconds(20));
        assertThat(recovered.get("last_time")).as("最后发生时间停留在最后一个无效点").isEqualTo(triggeredAt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox"
                + " WHERE event_type = 'ALARM_EVENT' AND event_id = ?", Long.class,
                "alarm:recovered:" + alarmId)).isEqualTo(1);
        JsonNode detail = alarmDetail(token, alarmId);
        JsonNode conditionHistory = detail.path("conditionHistory");
        assertThat(conditionHistory.size()).as("恢复写 CONDITION 条件历史").isEqualTo(1);
        JsonNode change = conditionHistory.get(0);
        assertThat(change.path("changeType").asText()).isEqualTo("CONDITION");
        assertThat(change.path("fromStatus").asText()).isEqualTo("ACTIVE");
        assertThat(change.path("toStatus").asText()).isEqualTo("RECOVERED");
        assertThat(change.path("operatorId").isNull()).as("条件变化不记操作者").isTrue();
        assertThat(change.path("operatorName").isNull()).isTrue();
        assertThat(change.path("remark").isNull()).isTrue();
        assertThat(detail.path("handleHistory").size()).as("恢复不写人工处置历史").isZero();
        Map<String, Object> validCandidate = candidate(bound, "POSITION_INVALID");
        assertThat(validCandidate.get("candidate_direction")).isEqualTo("VALID");
        assertThat(((Number) validCandidate.get("consecutive_count")).intValue()).isEqualTo(3);

        Map<String, Object> lastPosition = jdbc.queryForMap(
                "SELECT lng, lat FROM vehicle_last_position WHERE vehicle_id = ?", bound.vehicleId());
        assertThat(lastPosition.get("lng")).isNotNull();
        assertThat(lastPosition.get("lat")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT version FROM vehicle_last_position WHERE vehicle_id = ?",
                Long.class, bound.vehicleId())).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alarm WHERE vehicle_id = ?", Long.class,
                bound.vehicleId())).as("恢复后再无新事件").isEqualTo(1);
    }

    /** AC15/AC14：定位无效报警与位置同事务；写入失败整体回滚且不返回成功 ACK。 */
    @Test
    @Order(2)
    void positionInvalidAlarmSharesPositionTransactionAndFailureRollsBackWithoutAck() throws Exception {
        String token = login();
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime start = LocalDateTime.now(clock).withNano(0);
        LocalDateTime triggeredAt = start.plusSeconds(20);
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 1, invalidPosition(start, 900, 116.10));
            clock.advance(Duration.ofSeconds(10));
            sendAndExpectSuccess(terminal, 2, invalidPosition(start.plusSeconds(10), 900, 116.20));
        }

        jdbc.execute("ALTER TABLE alarm ADD CONSTRAINT injected_invalid_alarm_failure CHECK (vehicle_id <> "
                + bound.vehicleId() + ")");
        try (TerminalClient terminal = authenticated(bound)) {
            clock.advance(Duration.ofSeconds(10));
            terminal.send(0x0200, 1, invalidPosition(start.plusSeconds(20), 900, 116.30).encode());
            assertThat(terminal.socket.getInputStream().read()).as("报警写入失败不返回成功 ACK").isEqualTo(-1);
        } finally {
            jdbc.execute("ALTER TABLE alarm DROP CHECK injected_invalid_alarm_failure");
        }
        assertThat(alarmCount(bound)).as("报警与位置同事务回滚").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE vehicle_id = ?", Long.class,
                bound.vehicleId())).isEqualTo(2);
        assertThat(((Number) candidate(bound, "POSITION_INVALID").get("consecutive_count")).intValue())
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE event_type = 'ALARM_EVENT'"
                + " AND aggregate_id IN (SELECT id FROM alarm WHERE vehicle_id = ?)", Long.class,
                bound.vehicleId())).isZero();

        try (TerminalClient retry = authenticated(bound)) {
            sendAndExpectSuccess(retry, 1, invalidPosition(start.plusSeconds(20), 900, 116.30));
        }
        assertThat(alarmCount(bound)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE vehicle_id = ?", Long.class,
                bound.vehicleId())).isEqualTo(3);
        assertThat(((Number) candidate(bound, "POSITION_INVALID").get("consecutive_count")).intValue())
                .isEqualTo(3);
    }

    /** AC14/AC20：服务启动 180 秒宽限内不生成离线报警，宽限后对既有失联终端成立。 */
    @Test
    @Order(3)
    void startupGraceSuppressesOfflineAlarmFor180Seconds() throws Exception {
        String token = login();
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime staleSeenAt = LocalDateTime.now(clock).minusSeconds(300);
        jdbc.update("UPDATE terminal SET ever_authenticated = TRUE, last_seen_at = ? WHERE id = ?",
                staleSeenAt, bound.terminalId());

        offlineAlarms.scan();
        assertThat(offlineAlarmCount(bound)).as("启动宽限内不生成离线报警").isZero();

        clock.advance(Duration.ofSeconds(180));
        offlineAlarms.scan();

        Map<String, Object> alarm = offlineAlarm(bound);
        assertThat(alarm.get("condition_state")).isEqualTo("ACTIVE");
        assertThat(alarm.get("source")).isEqualTo("PLATFORM");
        assertThat(alarm.get("start_time")).isEqualTo(staleSeenAt.plusSeconds(180));
        assertThat(alarm.get("last_time")).isEqualTo(staleSeenAt.plusSeconds(180));
        assertThat(offlineAlarmOutboxCount(((Number) alarm.get("id")).longValue(), "created")).isEqualTo(1);
    }

    /** AC14/AC20：已鉴权、启用、曾接入终端 180 秒无有效通信成立，重连鉴权即恢复。 */
    @Test
    @Order(4)
    void offlineAlarmActivatesAfter180SecondsAndRecoversOnReconnect() throws Exception {
        String token = login();
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime expectedRecoveredAt;
        long alarmId;
        try (TerminalClient terminal = authenticated(bound)) {
            LocalDateTime lastSeen = jdbc.queryForObject("SELECT last_seen_at FROM terminal WHERE id = ?",
                    LocalDateTime.class, bound.terminalId());
            assertThat(lastSeen).isNotNull();
            clock.advance(Duration.ofSeconds(179));
            offlineAlarms.scan();
            assertThat(offlineAlarmCount(bound)).as("179 秒未达离线阈值").isZero();

            clock.advance(Duration.ofSeconds(2));
            offlineAlarms.scan();
            Map<String, Object> alarm = offlineAlarm(bound);
            assertThat(alarm.get("condition_state")).isEqualTo("ACTIVE");
            assertThat(alarm.get("handle_status")).isEqualTo("PENDING");
            assertThat(alarm.get("source")).isEqualTo("PLATFORM");
            assertThat(((Number) alarm.get("terminal_id")).longValue()).isEqualTo(bound.terminalId());
            assertThat(((Number) alarm.get("vehicle_id")).longValue()).isEqualTo(bound.vehicleId());
            assertThat(((Number) alarm.get("fleet_id_at_event")).longValue()).isEqualTo(bound.fleetId());
            assertThat(alarm.get("rule_id")).isNull();
            assertThat(alarm.get("rule_version")).isNull();
            assertThat(alarm.get("rule_snapshot")).isNull();
            assertThat(alarm.get("start_time")).isEqualTo(lastSeen.plusSeconds(180));
            assertThat(alarm.get("last_time")).isEqualTo(lastSeen.plusSeconds(180));
            assertThat(alarm.get("recovered_at")).isNull();
            assertThat(alarm.get("scope_changed_at")).isNull();
            alarmId = ((Number) alarm.get("id")).longValue();
            assertThat(offlineAlarmOutboxCount(alarmId, "created")).isEqualTo(1);
            expectedRecoveredAt = LocalDateTime.now(clock);
        }

        // 重连鉴权属于有效通信，恢复既有离线事件
        try (TerminalClient reconnect = authenticated(bound)) {
            assertThat(reconnect).isNotNull();
        }
        Map<String, Object> recovered = jdbc.queryForMap("SELECT * FROM alarm WHERE id = ?", alarmId);
        assertThat(recovered.get("condition_state")).isEqualTo("RECOVERED");
        assertThat(recovered.get("recovered_at")).isEqualTo(expectedRecoveredAt);
        assertThat(recovered.get("last_time")).as("恢复不改最后发生时间")
                .isEqualTo(recovered.get("start_time"));
        assertThat(offlineAlarmOutboxCount(alarmId, "recovered")).isEqualTo(1);

        JsonNode detail = alarmDetail(token, alarmId);
        JsonNode conditionHistory = detail.path("conditionHistory");
        assertThat(conditionHistory.size()).as("离线恢复写 CONDITION 条件历史").isEqualTo(1);
        JsonNode change = conditionHistory.get(0);
        assertThat(change.path("changeType").asText()).isEqualTo("CONDITION");
        assertThat(change.path("fromStatus").asText()).isEqualTo("ACTIVE");
        assertThat(change.path("toStatus").asText()).isEqualTo("RECOVERED");
        assertThat(change.path("operatorId").isNull()).as("条件变化不记操作者").isTrue();
        assertThat(change.path("operatorName").isNull()).isTrue();
        assertThat(change.path("remark").isNull()).isTrue();
        assertThat(detail.path("handleHistory").size()).as("恢复不写人工处置历史").isZero();

        offlineAlarms.scan();
        assertThat(offlineAlarmCount(bound)).as("恢复后不重复新事件").isEqualTo(1);
    }

    /** AC14：未接入、合法注销、停用终端/车辆与解绑均不生成离线报警。 */
    @Test
    @Order(5)
    void unconnectedLawfullyLoggedOutDisabledAndUnboundTerminalsDoNotAlarm() throws Exception {
        String token = login();

        BoundTerminal never = boundTerminal(token);
        offlineAlarms.scan();
        assertThat(offlineAlarmCount(never)).as("未接入终端不产生离线报警").isZero();

        BoundTerminal loggedOut = boundTerminal(token);
        try (TerminalClient terminal = authenticated(loggedOut)) {
            clock.advance(Duration.ofSeconds(10));
            terminal.send(0x0003, 1, new byte[0]);
            assertThat(TerminalGeneralResponse.parse(terminal.receive().body()).result()).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT last_logout_at FROM terminal WHERE id = ?",
                LocalDateTime.class, loggedOut.terminalId())).isNotNull();
        clock.advance(Duration.ofSeconds(200));
        offlineAlarms.scan();
        assertThat(offlineAlarmCount(loggedOut)).as("合法注销不产生离线报警").isZero();

        BoundTerminal disabled = boundTerminal(token);
        try (TerminalClient terminal = authenticated(disabled)) {
            assertThat(terminal).isNotNull();
        }
        mvc.perform(put("/api/v1/terminals/" + disabled.terminalId()).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(200));
        offlineAlarms.scan();
        assertThat(offlineAlarmCount(disabled)).as("停用终端不产生离线报警").isZero();

        BoundTerminal disabledVehicle = boundTerminal(token);
        try (TerminalClient terminal = authenticated(disabledVehicle)) {
            assertThat(terminal).isNotNull();
        }
        mvc.perform(put("/api/v1/vehicles/" + disabledVehicle.vehicleId()).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(200));
        offlineAlarms.scan();
        assertThat(offlineAlarmCount(disabledVehicle)).as("停用车辆不产生离线报警").isZero();

        BoundTerminal unbound = boundTerminal(token);
        try (TerminalClient terminal = authenticated(unbound)) {
            assertThat(terminal).isNotNull();
        }
        // 解绑生效时间必须晚于绑定起点
        clock.advance(Duration.ofSeconds(1));
        mvc.perform(put("/api/v1/vehicles/" + unbound.vehicleId() + "/terminal").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of())))
                .andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(200));
        offlineAlarms.scan();
        assertThat(offlineAlarmCount(unbound)).as("解绑终端不产生离线报警").isZero();
    }

    /** AC14：合法鉴权补传报文接收可更新通信并恢复离线，但不推进当前位置与超速。 */
    @Test
    @Order(6)
    void backfillReceiveRecoversOfflineWithoutAdvancingRealtimeState() throws Exception {
        String token = login();
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime realtimeAt = LocalDateTime.now(clock).withNano(0);
        long alarmId;
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 1, position(realtimeAt, 600, 116.10));
            assertThat(jdbc.queryForObject("SELECT gps_time FROM vehicle_last_position WHERE vehicle_id = ?",
                    LocalDateTime.class, bound.vehicleId())).isEqualTo(realtimeAt);

            clock.advance(Duration.ofSeconds(181));
            offlineAlarms.scan();
            Map<String, Object> alarm = offlineAlarm(bound);
            assertThat(alarm.get("condition_state")).isEqualTo("ACTIVE");
            alarmId = ((Number) alarm.get("id")).longValue();

            LocalDateTime backfillAt = realtimeAt.plusSeconds(1);
            LocalDateTime receivedAt = LocalDateTime.now(clock);
            Map<String, Object> overspeedBefore = jdbc.queryForMap(
                    "SELECT candidate_direction, consecutive_count, last_point_time FROM alarm_candidate_state"
                            + " WHERE vehicle_id = ? AND alarm_type = 'OVERSPEED'", bound.vehicleId());
            // 120 km/h 若被误判为实时点会立即进入超速候选
            sendAndExpectSuccess(terminal, 2, position(backfillAt, 1200, 116.20));

            assertThat(jdbc.queryForObject("SELECT last_seen_at FROM terminal WHERE id = ?",
                    LocalDateTime.class, bound.terminalId()))
                    .as("补传报文接收更新最后通信时间").isEqualTo(receivedAt);
            Map<String, Object> recovered = jdbc.queryForMap("SELECT * FROM alarm WHERE id = ?", alarmId);
            assertThat(recovered.get("condition_state")).isEqualTo("RECOVERED");
            assertThat(recovered.get("recovered_at")).isEqualTo(receivedAt);
            assertThat(jdbc.queryForObject("SELECT gps_time FROM vehicle_last_position WHERE vehicle_id = ?",
                    LocalDateTime.class, bound.vehicleId()))
                    .as("包内历史定位不推进当前位置").isEqualTo(realtimeAt);
            assertThat(jdbc.queryForObject("SELECT version FROM vehicle_last_position WHERE vehicle_id = ?",
                    Long.class, bound.vehicleId())).isEqualTo(1L);
            Map<String, Object> overspeedAfter = jdbc.queryForMap(
                    "SELECT candidate_direction, consecutive_count, last_point_time FROM alarm_candidate_state"
                            + " WHERE vehicle_id = ? AND alarm_type = 'OVERSPEED'", bound.vehicleId());
            assertThat(overspeedAfter).as("补传点不进入超速候选").isEqualTo(overspeedBefore);
            assertThat(jdbc.queryForObject("SELECT report_type FROM track_point WHERE vehicle_id = ?"
                    + " AND gps_time = ?", String.class, bound.vehicleId(), backfillAt)).isEqualTo("HISTORY");
            assertThat(offlineAlarmOutboxCount(alarmId, "recovered")).isEqualTo(1);
        }
    }

    /** AC08/AC15：保留期外与超容差未来点边界明确区分，均双重留痕且不落轨迹。 */
    @Test
    @Order(7)
    void retentionOutsideAndFutureBeyondToleranceWriteDistinctEvidence() throws Exception {
        String token = login();
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        LocalDateTime outside = now.toLocalDate().minusDays(31).atTime(10, 0);
        LocalDateTime future = now.plusSeconds(120);
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 1, position(outside, 600, 116.10));
            sendAndExpectSuccess(terminal, 2, position(future, 600, 116.20));
        }

        List<String> reasons = jdbc.queryForList("SELECT reason FROM telemetry_anomaly WHERE terminal_id = ?"
                + " ORDER BY id", String.class, bound.terminalId());
        assertThat(reasons).containsExactly("RETENTION_OUTSIDE", "FUTURE_BEYOND_TOLERANCE");
        Map<String, Object> rejection = jdbc.queryForMap("SELECT * FROM track_retention_rejection"
                + " WHERE terminal_id = ?", bound.terminalId());
        assertThat(rejection.get("reason")).isEqualTo("OUTSIDE_RETENTION");
        assertThat(rejection.get("gps_time")).isEqualTo(outside);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_retention_rejection WHERE terminal_id = ?",
                Long.class, bound.terminalId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE vehicle_id = ?", Long.class,
                bound.vehicleId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_last_position WHERE vehicle_id = ?",
                Long.class, bound.vehicleId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM telemetry_anomaly WHERE terminal_id = ?"
                + " AND reason = 'FUTURE_BEYOND_TOLERANCE'", Long.class, bound.terminalId()))
                .as("超容差未来点不写保留拒绝").isEqualTo(1);
    }

    /** AC14：停用、解绑不新造离线报警，既有 ACTIVE 保留并标注监控范围变化。 */
    @Test
    @Order(8)
    void managementOperationsKeepActiveOfflineAlarmAndMarkScopeChange() throws Exception {
        String token = login();
        BoundTerminal terminalDisabled = boundTerminal(token);
        long terminalDisabledAlarm = activateOfflineAlarm(terminalDisabled);
        BoundTerminal unbound = boundTerminal(token);
        long unboundAlarm = activateOfflineAlarm(unbound);
        BoundTerminal vehicleDisabled = boundTerminal(token);
        long vehicleDisabledAlarm = activateOfflineAlarm(vehicleDisabled);

        mvc.perform(put("/api/v1/terminals/" + terminalDisabled.terminalId()).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/vehicles/" + unbound.vehicleId() + "/terminal").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of())))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/vehicles/" + vehicleDisabled.vehicleId()).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isOk());

        List<Long> alarmIds = List.of(terminalDisabledAlarm, unboundAlarm, vehicleDisabledAlarm);
        for (long alarmId : alarmIds) {
            Map<String, Object> alarm = jdbc.queryForMap("SELECT * FROM alarm WHERE id = ?", alarmId);
            assertThat(alarm.get("condition_state")).as("管理操作不自动恢复").isEqualTo("ACTIVE");
            assertThat(alarm.get("recovered_at")).isNull();
            assertThat(alarm.get("scope_changed_at")).as("标注监控范围变化").isNotNull();
        }

        clock.advance(Duration.ofSeconds(300));
        offlineAlarms.scan();
        assertThat(offlineAlarmCount(terminalDisabled)).isEqualTo(1);
        assertThat(offlineAlarmCount(unbound)).isEqualTo(1);
        assertThat(offlineAlarmCount(vehicleDisabled)).isEqualTo(1);
        for (long alarmId : alarmIds) {
            assertThat(jdbc.queryForObject("SELECT condition_state FROM alarm WHERE id = ?", String.class, alarmId))
                    .isEqualTo("ACTIVE");
        }
    }

    @Test
    @Order(9)
    void terminalFlagsUseVerifiedReasonsAndNeverMergeWithPlatformAlarms() throws Exception {
        String token = login();
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime start = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            // 表 25，DWORD 80 00 00 77：0/1/2/4/5/6 同时上报，bit 31 不在本子集。
            PositionReport first = flagged(start, 0x8000_0077L, false);
            sendAndExpectSuccess(terminal, 1, first);
            sendAndExpectSuccess(terminal, 2, first); // 相同报文重传
            assertThat(jdbc.queryForList("SELECT alarm_type FROM alarm WHERE vehicle_id=? AND source='TERMINAL'",
                    String.class, bound.vehicleId())).containsExactlyInAnyOrder("EMERGENCY", "OVERSPEED", "FATIGUE_DRIVING",
                    "GNSS_MODULE_FAULT", "GNSS_ANTENNA_DISCONNECTED", "GNSS_ANTENNA_SHORT");
            assertThat(jdbc.queryForObject("SELECT alarm_flag FROM track_point WHERE terminal_id=? AND gps_time=?",
                    Long.class, bound.terminalId(), start)).isEqualTo(0x8000_0077L);
            // 三个有效超速点另成立平台超速；终端持续置位不逐点新建报警。
            for (int i = 1; i <= 3; i++) {
                clock.advance(Duration.ofSeconds(3));
                sendAndExpectSuccess(terminal, 2 + i, flagged(start.plusSeconds(3L * i), 0x77, true));
            }
            // 仅保留 bit 5，其余持续位各自恢复；平台速度依然超限。
            clock.advance(Duration.ofSeconds(3));
            sendAndExpectSuccess(terminal, 6, flagged(start.plusSeconds(12), 0x20, true));
            assertThat(jdbc.queryForList("SELECT alarm_type FROM alarm WHERE vehicle_id=? AND source='TERMINAL'"
                    + " AND condition_state='RECOVERED' AND last_time=? AND recovered_at=? AND handle_status='PENDING'",
                    String.class, bound.vehicleId(), start.plusSeconds(9), start.plusSeconds(12)))
                    .containsExactlyInAnyOrder("OVERSPEED", "FATIGUE_DRIVING", "GNSS_MODULE_FAULT", "GNSS_ANTENNA_SHORT");
            assertThat(jdbc.queryForList("SELECT source FROM alarm WHERE vehicle_id=? AND alarm_type='OVERSPEED'"
                    + " AND condition_state='ACTIVE'", String.class, bound.vehicleId())).containsExactly("PLATFORM");
            assertThat(jdbc.queryForList("SELECT alarm_type FROM alarm WHERE vehicle_id=? AND source='TERMINAL'"
                    + " AND condition_state='ACTIVE'", String.class, bound.vehicleId())).containsExactly("GNSS_ANTENNA_DISCONNECTED");
            long emergency = jdbc.queryForObject("SELECT id FROM alarm WHERE vehicle_id=? AND alarm_type='EMERGENCY'"
                    + " AND condition_state='EVENT' AND recovered_at IS NULL", Long.class, bound.vehicleId());
            assertThat(alarmDetail(token, emergency).path("source").asText()).isEqualTo("TERMINAL");
            assertThat(alarmCount(bound)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox o JOIN alarm a ON a.id=o.aggregate_id"
                    + " WHERE a.vehicle_id=? AND a.source='TERMINAL' AND o.event_type='ALARM_EVENT'",
                    Long.class, bound.vehicleId())).isEqualTo(10); // 6 created + 4 recovered
            assertThat(jdbc.queryForObject("SELECT count(*) FROM alarm_handle_log l JOIN alarm a ON a.id=l.alarm_id"
                    + " WHERE a.vehicle_id=? AND a.source='TERMINAL' AND l.change_type='CONDITION'",
                    Long.class, bound.vehicleId())).isEqualTo(4);
        }
    }

    @Test
    @Order(10)
    void historicalFlagsCannotClearLiveFaultAndFailedNotificationDoesNotConsumeAnEdge() throws Exception {
        BoundTerminal bound = boundTerminal(login());
        LocalDateTime start = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            clock.advance(Duration.ofSeconds(5));
            sendAndExpectSuccess(terminal, 1, flagged(start.plusSeconds(5), 0x11, false));
            // 第一条是无效坐标，没有 vehicle_last_position；仍不能被迟到的零值恢复。
            sendAndExpectSuccess(terminal, 2, flagged(start.plusSeconds(1), 0, true));
            assertThat(jdbc.queryForObject("SELECT report_type FROM track_point WHERE terminal_id=? AND gps_time=?",
                    String.class, bound.terminalId(), start.plusSeconds(1))).isEqualTo("HISTORY");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_last_position WHERE vehicle_id=?",
                    Long.class, bound.vehicleId())).isZero();
            clock.advance(Duration.ofSeconds(1));
            terminal.send(0x0704, 3, new PositionBatch(PositionBatch.TYPE_BLIND_AREA,
                    List.of(flagged(start.plusSeconds(6), 0, true))).encode());
            assertThat(TerminalGeneralResponse.parse(terminal.receive().body()).result()).isZero();
            assertThat(jdbc.queryForObject("SELECT report_type FROM track_point WHERE terminal_id=? AND gps_time=?",
                    String.class, bound.terminalId(), start.plusSeconds(6))).isEqualTo("HISTORY");
            assertThat(jdbc.queryForObject("SELECT condition_state FROM alarm WHERE vehicle_id=?"
                    + " AND alarm_type='GNSS_MODULE_FAULT'", String.class, bound.vehicleId())).isEqualTo("ACTIVE");
        }
        long id = jdbc.queryForObject("SELECT id FROM alarm WHERE vehicle_id=? AND alarm_type='GNSS_MODULE_FAULT'",
                Long.class, bound.vehicleId());
        jdbc.execute("ALTER TABLE notification_outbox ADD CONSTRAINT fail_terminal_recovery CHECK "
                + "(event_id <> 'alarm:recovered:" + id + "')");
        clock.advance(Duration.ofSeconds(2));
        PositionReport cleared = flagged(start.plusSeconds(8), 0x8000_0000L, true);
        try (TerminalClient terminal = authenticated(bound)) {
            terminal.send(0x0200, 1, cleared.encode());
            assertThat(terminal.socket.getInputStream().read()).as("回滚时不回成功 ACK").isEqualTo(-1);
        } finally {
            jdbc.execute("ALTER TABLE notification_outbox DROP CHECK fail_terminal_recovery");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE terminal_id=? AND gps_time=?",
                Long.class, bound.terminalId(), cleared.time())).isZero();
        assertThat(jdbc.queryForObject("SELECT condition_state FROM alarm WHERE id=?", String.class, id)).isEqualTo("ACTIVE");
        try (TerminalClient retry = authenticated(bound)) {
            sendAndExpectSuccess(retry, 1, cleared);
            assertThat(jdbc.queryForObject("SELECT recovered_at FROM alarm WHERE id=?", LocalDateTime.class, id))
                    .isEqualTo(cleared.time());
            clock.advance(Duration.ofSeconds(1));
            sendAndExpectSuccess(retry, 2, flagged(start.plusSeconds(9), 0x11, true));
        }
        assertThat(jdbc.queryForList("SELECT alarm_type FROM alarm WHERE vehicle_id=? AND source='TERMINAL'"
                + " ORDER BY id", String.class, bound.vehicleId()))
                .containsExactlyInAnyOrder("EMERGENCY", "EMERGENCY", "GNSS_MODULE_FAULT", "GNSS_MODULE_FAULT");
    }

    @Test
    @Order(11)
    void individualAlarmDwordsProduceOnlyTheirTable25Reason() throws Exception {
        BoundTerminal bound = boundTerminal(login());
        // 直接给 0x0200 正文的前四字节，不由生产映射或 PositionReport.encode 推算测试的报警位。
        Map<Integer, String> oneHot = Map.of(0x01, "EMERGENCY", 0x02, "OVERSPEED", 0x04, "FATIGUE_DRIVING",
                0x10, "GNSS_MODULE_FAULT", 0x20, "GNSS_ANTENNA_DISCONNECTED", 0x40, "GNSS_ANTENNA_SHORT");
        try (TerminalClient terminal = authenticated(bound)) {
            int serial = 10;
            for (var entry : oneHot.entrySet()) {
                LocalDateTime time = LocalDateTime.now(clock).withNano(0);
                byte[] body = position(time, 0, 116.10).encode();
                body[3] = entry.getKey().byteValue();
                terminal.send(0x0200, serial++, body);
                assertThat(TerminalGeneralResponse.parse(terminal.receive().body()).result()).isZero();
                assertThat(jdbc.queryForList("SELECT alarm_type FROM alarm WHERE vehicle_id=? AND source='TERMINAL'"
                        + " AND start_time=?", String.class, bound.vehicleId(), time)).containsExactly(entry.getValue());
                clock.advance(Duration.ofSeconds(1));
            }
        }
    }

    @Test
    @Order(12)
    void rebindingSameDeviceDoesNotCreateAnotherFaultOrEmergency() throws Exception {
        String token = login();
        BoundTerminal bound = boundTerminal(token);
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 1, flagged(LocalDateTime.now(clock), 0x11, false));
        }
        clock.advance(Duration.ofSeconds(2));
        String bindingPath = "/api/v1/vehicles/" + bound.vehicleId() + "/terminal";
        mvc.perform(put(bindingPath).header("satoken", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(2));
        mvc.perform(put(bindingPath).header("satoken", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("terminalId", bound.terminalId())))).andExpect(status().isOk());
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 1, flagged(LocalDateTime.now(clock), 0x11, true));
            assertThat(alarmCount(bound)).isEqualTo(2);
            clock.advance(Duration.ofSeconds(1));
            sendAndExpectSuccess(terminal, 2, flagged(LocalDateTime.now(clock), 0, true));
        }
        assertThat(jdbc.queryForList("SELECT condition_state FROM alarm WHERE vehicle_id=? AND alarm_type='GNSS_MODULE_FAULT'",
                String.class, bound.vehicleId())).containsExactly("RECOVERED");
        assertThat(jdbc.queryForList("SELECT condition_state FROM alarm WHERE vehicle_id=? AND alarm_type='EMERGENCY'",
                String.class, bound.vehicleId())).containsExactly("EVENT");
    }

    private PositionReport flagged(LocalDateTime time, long flags, boolean valid) {
        return new PositionReport(flags, valid ? 3 : 1, 39.908, 116.10, 10, 900, 90, time, List.of());
    }

    private long activateOfflineAlarm(BoundTerminal bound) throws Exception {
        try (TerminalClient terminal = authenticated(bound)) {
            assertThat(terminal).isNotNull();
        }
        clock.advance(Duration.ofSeconds(181));
        offlineAlarms.scan();
        return ((Number) offlineAlarm(bound).get("id")).longValue();
    }

    private Map<String, Object> offlineAlarm(BoundTerminal bound) {
        return jdbc.queryForMap("SELECT * FROM alarm WHERE vehicle_id = ? AND alarm_type = 'OFFLINE'"
                + " ORDER BY id DESC LIMIT 1", bound.vehicleId());
    }

    private JsonNode alarmDetail(String token, long alarmId) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/alarms/" + alarmId).header("satoken", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private long offlineAlarmCount(BoundTerminal bound) {
        return jdbc.queryForObject("SELECT count(*) FROM alarm WHERE vehicle_id = ? AND alarm_type = 'OFFLINE'",
                Long.class, bound.vehicleId());
    }

    private long offlineAlarmOutboxCount(long alarmId, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE event_type = 'ALARM_EVENT'"
                + " AND event_id = ?", Long.class, "alarm:" + action + ":" + alarmId);
    }
    private PositionReport position(LocalDateTime time, int speed, double longitude) {
        return new PositionReport(0, 3, 39.908, longitude, 10, speed, 90, time, List.of());
    }

    /** 状态位 bit1 为 0 表示定位无效；速度与坐标字段仍保留原始上报值用于比对。 */
    private PositionReport invalidPosition(LocalDateTime time, int speed, double longitude) {
        return new PositionReport(0, 1, 39.908, longitude, 10, speed, 90, time, List.of());
    }

    private Map<String, Object> candidate(BoundTerminal bound, String alarmType) {
        return jdbc.queryForMap("""
                SELECT candidate_direction, consecutive_count, last_point_time, rule_id, rule_version
                FROM alarm_candidate_state
                WHERE vehicle_id = ? AND alarm_type = ? AND source = 'PLATFORM' AND business_dimension = ''
                """, bound.vehicleId(), alarmType);
    }

    private long candidateCount(BoundTerminal bound, String alarmType) {
        return jdbc.queryForObject("SELECT count(*) FROM alarm_candidate_state WHERE vehicle_id = ?"
                + " AND alarm_type = ?", Long.class, bound.vehicleId(), alarmType);
    }

    private long alarmCount(BoundTerminal bound) {
        return jdbc.queryForObject("SELECT count(*) FROM alarm WHERE vehicle_id = ?", Long.class,
                bound.vehicleId());
    }

    private void sendAndExpectSuccess(TerminalClient terminal, int serial, PositionReport report) throws Exception {
        terminal.send(0x0200, serial, report.encode());
        TerminalGeneralResponse response = TerminalGeneralResponse.parse(terminal.receive().body());
        assertThat(response.responseId()).isEqualTo(0x0200);
        assertThat(response.responseSerial()).isEqualTo(serial);
        assertThat(response.result()).isZero();
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

    private String login() throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", ADMIN, "password", PASSWORD))))
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

    private record BoundTerminal(long terminalId, long vehicleId, long fleetId, String terminalNo) { }

    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock ticket14Clock() {
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