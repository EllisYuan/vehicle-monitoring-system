package com.vms.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import com.vms.protocol.PositionReport;
import com.vms.support.IntegrationContainers;
import com.vms.support.MutableClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
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
import java.util.concurrent.Callable;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 13：报警列表、详情与处置闭环；经真实 HTTP 与真实数据库核对。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "vms.bootstrap-admin.username=handle-admin",
        "vms.bootstrap-admin.password=Handle-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0"
})
@Import({IntegrationContainers.class, AlarmHandlingFlowTest.TimeConfiguration.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AlarmHandlingFlowTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired OverspeedAlarmParticipant participant;
    @LocalServerPort int httpPort;

    @Test
    @Order(1)
    void listAndDetailExposeBothStatesDriverSnapshotPositionAndMasking() throws Exception {
        String token = login("handle-admin", "Handle-Pass-2026");
        BoundTerminal granted = boundTerminal(token);
        BoundTerminal other = boundTerminal(token);
        long originalDriver = createDriver(token, granted.fleetId(), "13812345678");
        bindDriver(token, granted.vehicleId(), originalDriver);
        LocalDateTime start = LocalDateTime.now(clock).withNano(0);
        long alarmId = insertAlarm(granted, originalDriver, "ACTIVE", "PENDING", start, 96.0);
        long otherAlarmId = insertAlarm(other, null, "ACTIVE", "PENDING", start, 95.0);

        JsonNode all = get(token, "/alarms");
        assertThat(ids(all)).contains(alarmId, otherAlarmId);
        JsonNode row = find(all, alarmId);
        assertThat(row.path("conditionState").asText()).isEqualTo("ACTIVE");
        assertThat(row.path("handleStatus").asText()).isEqualTo("PENDING");
        assertThat(row.path("driverId").asLong()).isEqualTo(originalDriver);
        assertThat(row.path("plateNo").asText()).isEqualTo(granted.plateNo());

        JsonNode detail = get(token, "/alarms/" + alarmId);
        assertThat(detail.path("conditionState").asText()).isEqualTo("ACTIVE");
        assertThat(detail.path("handleStatus").asText()).isEqualTo("PENDING");
        assertThat(detail.path("source").asText()).isEqualTo("PLATFORM");
        assertThat(detail.path("alarmType").asText()).isEqualTo("OVERSPEED");
        assertThat(detail.path("driver").path("id").asLong()).isEqualTo(originalDriver);
        assertThat(detail.path("driver").path("phone").asText()).isEqualTo("13812345678");
        assertThat(detail.path("ruleSnapshot").path("thresholdKph").asInt()).isEqualTo(80);
        assertThat(detail.path("ruleSnapshot").path("scopeType").asText()).isEqualTo("GLOBAL");
        assertThat(detail.path("ruleSnapshot").path("ruleVersion").asLong()).isEqualTo(1L);
        assertThat(detail.path("position").path("lng").asDouble()).isEqualTo(116.48);
        assertThat(detail.path("position").path("lat").asDouble()).isEqualTo(39.908);
        assertThat(detail.path("position").path("speedKph").asDouble()).isEqualTo(96.0);
        assertThat(detail.path("position").path("gpsTime").asText()).isNotBlank();
        assertThat(detail.path("handleHistory").isArray()).isTrue();
        assertThat(detail.path("handleHistory").size()).isZero();

        long replacement = createDriver(token, granted.fleetId(), "13900001111");
        clock.advance(Duration.ofSeconds(1));
        bindDriver(token, granted.vehicleId(), replacement);
        JsonNode afterRebind = get(token, "/alarms/" + alarmId);
        assertThat(afterRebind.path("driver").path("id").asLong()).isEqualTo(originalDriver);
        assertThat(afterRebind.path("driver").path("name").asText())
                .isEqualTo(jdbc.queryForObject("SELECT name FROM driver WHERE id = ?", String.class, originalDriver));
        assertThat(find(get(token, "/alarms"), alarmId).path("driverId").asLong()).isEqualTo(originalDriver);

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String leaderName = "alarm-leader-" + suffix;
        create(token, "/users", Map.of("username", leaderName, "password", "Leader-Pass-2026",
                "roleCode", "FLEET_LEADER", "fleetIds", List.of(granted.fleetId())));
        String leader = login(leaderName, "Leader-Pass-2026");
        JsonNode leaderList = get(leader, "/alarms");
        assertThat(ids(leaderList)).contains(alarmId);
        assertThat(ids(leaderList)).doesNotContain(otherAlarmId);
        JsonNode leaderDetail = get(leader, "/alarms/" + alarmId);
        assertThat(leaderDetail.path("driver").path("phone").asText()).isEqualTo("138****5678");
        assertThat(leaderDetail.path("handleHistory").isArray()).isTrue();
        assertThat(status("GET", "/alarms/" + otherAlarmId, leader, null)).isEqualTo(403);

        assertThat(status("GET", "/alarms/" + alarmId, null, null)).isEqualTo(401);
        assertThat(status("GET", "/alarms/999999999", token, null)).isEqualTo(404);
    }

    @Test
    @Order(2)
    void handleStateMachineLogsEachStepSeparatelyAndRejectsIllegalTransitions() throws Exception {
        String token = login("handle-admin", "Handle-Pass-2026");
        long actorId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='handle-admin'", Long.class);
        BoundTerminal bound = boundTerminal(token);
        long alarmId = insertAlarm(bound, null, "ACTIVE", "PENDING", LocalDateTime.now(clock).withNano(0), 97.0);

        HttpCall started = call("POST", "/alarms/" + alarmId + "/handle", token,
                Map.of("targetStatus", "PROCESSING", "remark", "开始核查"));
        assertThat(started.status()).as(started.raw()).isEqualTo(200);
        assertThat(started.body().path("handleStatus").asText()).isEqualTo("PROCESSING");
        assertThat(started.body().path("conditionState").asText()).isEqualTo("ACTIVE");
        Map<String, Object> firstLog = jdbc.queryForMap(
                "SELECT * FROM alarm_handle_log WHERE alarm_id=? AND change_type='HANDLE'", alarmId);
        assertThat(firstLog.get("from_status")).isEqualTo("PENDING");
        assertThat(firstLog.get("to_status")).isEqualTo("PROCESSING");
        assertThat(((Number) firstLog.get("operator_id")).longValue()).isEqualTo(actorId);
        assertThat(firstLog.get("remark")).isEqualTo("开始核查");
        assertThat(firstLog.get("created_at")).isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox
                WHERE aggregate_id=? AND event_type='ALARM_EVENT' AND event_id LIKE 'alarm:handle:%'
                """, Long.class, alarmId)).isEqualTo(1L);

        HttpCall illegal = call("POST", "/alarms/" + alarmId + "/handle", token,
                Map.of("targetStatus", "PROCESSING", "remark", "重复处理"));
        assertThat(illegal.status()).isEqualTo(409);
        assertThat(illegal.body().path("code").asText()).isEqualTo("ALARM_HANDLE_ILLEGAL");
        assertThat(jdbc.queryForObject("SELECT handle_status FROM alarm WHERE id=?", String.class, alarmId))
                .isEqualTo("PROCESSING");
        assertThat(handleLogCount(alarmId)).isEqualTo(1L);

        HttpCall finished = call("POST", "/alarms/" + alarmId + "/handle", token,
                Map.of("targetStatus", "HANDLED", "remark", "已联系司机"));
        assertThat(finished.status()).as(finished.raw()).isEqualTo(200);
        assertThat(finished.body().path("handleStatus").asText()).isEqualTo("HANDLED");

        JsonNode detail = get(token, "/alarms/" + alarmId);
        JsonNode history = detail.path("handleHistory");
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).path("fromStatus").asText()).isEqualTo("PENDING");
        assertThat(history.get(0).path("toStatus").asText()).isEqualTo("PROCESSING");
        assertThat(history.get(1).path("fromStatus").asText()).isEqualTo("PROCESSING");
        assertThat(history.get(1).path("toStatus").asText()).isEqualTo("HANDLED");
        assertThat(history.get(1).path("operatorId").asLong()).isEqualTo(actorId);
        assertThat(history.get(1).path("operatorName").asText()).isEqualTo("handle-admin");
        assertThat(history.get(1).path("remark").asText()).isEqualTo("已联系司机");

        HttpCall terminal = call("POST", "/alarms/" + alarmId + "/handle", token,
                Map.of("targetStatus", "IGNORED", "remark", "尝试重开"));
        assertThat(terminal.status()).isEqualTo(409);
        assertThat(terminal.body().path("code").asText()).isEqualTo("ALARM_HANDLE_ILLEGAL");
        assertThat(get(token, "/alarms/" + alarmId).path("handleHistory").size()).isEqualTo(2);

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT * FROM audit_log WHERE category='ALARM' AND action='HANDLE' AND target=?
                ORDER BY id DESC LIMIT 1
                """, "alarm:" + alarmId);
        assertThat(audit.get("result")).isEqualTo("SUCCESS");
        assertThat(((Number) audit.get("actor_id")).longValue()).isEqualTo(actorId);

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String leaderName = "readonly-leader-" + suffix;
        create(token, "/users", Map.of("username", leaderName, "password", "Leader-Pass-2026",
                "roleCode", "FLEET_LEADER", "fleetIds", List.of(bound.fleetId())));
        String leader = login(leaderName, "Leader-Pass-2026");
        HttpCall denied = call("POST", "/alarms/" + alarmId + "/handle", leader,
                Map.of("targetStatus", "IGNORED", "remark", "车队长越权"));
        assertThat(denied.status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT handle_status FROM alarm WHERE id=?", String.class, alarmId))
                .isEqualTo("HANDLED");

        long recoveredId = insertAlarm(boundTerminal(token), null, "RECOVERED", "PENDING",
                LocalDateTime.now(clock).withNano(0), 90.0);
        HttpCall recoveredStart = call("POST", "/alarms/" + recoveredId + "/handle", token,
                Map.of("targetStatus", "PROCESSING", "remark", "恢复后仍可处置"));
        assertThat(recoveredStart.status()).as(recoveredStart.raw()).isEqualTo(200);
        assertThat(recoveredStart.body().path("conditionState").asText()).isEqualTo("RECOVERED");
    }

    @Test
    @Order(3)
    void recoveryLogsConditionChangeSeparatelyAndNewCycleCreatesNewAlarm() throws Exception {
        String token = login("handle-admin", "Handle-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime base = LocalDateTime.now(clock).withNano(0).plusMinutes(5);

        applyPoint(bound, base, 900);
        applyPoint(bound, base.plusSeconds(10), 900);
        applyPoint(bound, base.plusSeconds(20), 920);
        long firstAlarmId = jdbc.queryForObject(
                "SELECT id FROM alarm WHERE vehicle_id=? AND alarm_type='OVERSPEED'", Long.class, bound.vehicleId());
        assertThat(jdbc.queryForObject("SELECT condition_state FROM alarm WHERE id=?", String.class, firstAlarmId))
                .isEqualTo("ACTIVE");
        assertThat(get(token, "/alarms/" + firstAlarmId).path("conditionHistory").size()).isZero();

        applyPoint(bound, base.plusSeconds(30), 700);
        applyPoint(bound, base.plusSeconds(40), 690);
        applyPoint(bound, base.plusSeconds(50), 680);

        assertThat(jdbc.queryForObject("SELECT condition_state FROM alarm WHERE id=?", String.class, firstAlarmId))
                .isEqualTo("RECOVERED");
        assertThat(jdbc.queryForObject("SELECT handle_status FROM alarm WHERE id=?", String.class, firstAlarmId))
                .as("恢复不改变处置状态").isEqualTo("PENDING");
        JsonNode recoveredDetail = get(token, "/alarms/" + firstAlarmId);
        assertThat(recoveredDetail.path("conditionState").asText()).isEqualTo("RECOVERED");
        assertThat(recoveredDetail.path("handleStatus").asText()).isEqualTo("PENDING");
        JsonNode conditionHistory = recoveredDetail.path("conditionHistory");
        assertThat(conditionHistory.size()).isEqualTo(1);
        JsonNode change = conditionHistory.get(0);
        assertThat(change.path("changeType").asText()).isEqualTo("CONDITION");
        assertThat(change.path("fromStatus").asText()).isEqualTo("ACTIVE");
        assertThat(change.path("toStatus").asText()).isEqualTo("RECOVERED");
        assertThat(change.path("operatorId").isNull()).isTrue();
        assertThat(change.path("remark").isNull()).isTrue();
        assertThat(change.path("createdAt").asText()).isNotBlank();
        assertThat(recoveredDetail.path("handleHistory").size()).as("条件变化不写人工处置历史").isZero();

        HttpCall handled = call("POST", "/alarms/" + firstAlarmId + "/handle", token,
                Map.of("targetStatus", "PROCESSING", "remark", "恢复后补处置"));
        assertThat(handled.status()).as(handled.raw()).isEqualTo(200);
        JsonNode afterHandle = get(token, "/alarms/" + firstAlarmId);
        assertThat(afterHandle.path("conditionState").asText()).isEqualTo("RECOVERED");
        assertThat(afterHandle.path("handleStatus").asText()).isEqualTo("PROCESSING");
        assertThat(afterHandle.path("conditionHistory").size()).isEqualTo(1);
        assertThat(afterHandle.path("handleHistory").size()).isEqualTo(1);

        applyPoint(bound, base.plusSeconds(60), 900);
        applyPoint(bound, base.plusSeconds(70), 910);
        applyPoint(bound, base.plusSeconds(80), 930);
        List<Long> alarmIds = jdbc.queryForList(
                "SELECT id FROM alarm WHERE vehicle_id=? ORDER BY id", Long.class, bound.vehicleId());
        assertThat(alarmIds).hasSize(2);
        long secondAlarmId = alarmIds.get(1);
        assertThat(secondAlarmId).isNotEqualTo(firstAlarmId);
        assertThat(jdbc.queryForObject("SELECT condition_state FROM alarm WHERE id=?", String.class, firstAlarmId))
                .isEqualTo("RECOVERED");
        assertThat(jdbc.queryForObject("SELECT handle_status FROM alarm WHERE id=?", String.class, firstAlarmId))
                .isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT condition_state FROM alarm WHERE id=?", String.class, secondAlarmId))
                .isEqualTo("ACTIVE");
        assertThat(get(token, "/alarms/" + firstAlarmId).path("conditionHistory").size()).isEqualTo(1);
    }

    @Test
    @Order(4)
    void concurrentHandlingUsesCurrentStatusConditionalUpdate() throws Exception {
        String token = login("handle-admin", "Handle-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        long alarmId = insertAlarm(bound, null, "ACTIVE", "PENDING", LocalDateTime.now(clock).withNano(0), 98.0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<HttpCall> task = () -> {
                ready.countDown();
                go.await();
                return call("POST", "/alarms/" + alarmId + "/handle", token,
                        Map.of("targetStatus", "PROCESSING", "remark", "并发处置"));
            };
            Future<HttpCall> first = pool.submit(task);
            Future<HttpCall> second = pool.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<HttpCall> results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(results.stream().map(HttpCall::status).toList()).containsExactlyInAnyOrder(200, 409);
            HttpCall loser = results.stream().filter(result -> result.status() == 409).findFirst().orElseThrow();
            assertThat(loser.body().path("code").asText()).isEqualTo("ALARM_HANDLE_ILLEGAL");
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT handle_status FROM alarm WHERE id=?", String.class, alarmId))
                .isEqualTo("PROCESSING");
        assertThat(handleLogCount(alarmId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox
                WHERE aggregate_id=? AND event_type='ALARM_EVENT' AND event_id LIKE 'alarm:handle:%'
                """, Long.class, alarmId)).isEqualTo(1L);
    }

    @Test
    @Order(5)
    void handlePushesAlarmEventAfterCommitAndOutboxMarkedDone() throws Exception {
        String token = login("handle-admin", "Handle-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        long alarmId = insertAlarm(bound, null, "ACTIVE", "PENDING", LocalDateTime.now(clock).withNano(0), 99.0);
        try (WsObserver observer = subscribe(token)) {
            HttpCall handled = call("POST", "/alarms/" + alarmId + "/handle", token,
                    Map.of("targetStatus", "PROCESSING", "remark", "提交后推送"));
            assertThat(handled.status()).as(handled.raw()).isEqualTo(200);
            long logId = handled.body().path("logId").asLong();
            JsonNode frame = awaitFrame(observer, "alarm_event",
                    node -> node.path("aggregate_id").asLong() == alarmId
                            && "PROCESSING".equals(node.path("payload").path("handleStatus").asText()), 20000);
            assertThat(frame).as("处置提交后应收到报警事件").isNotNull();
            assertThat(frame.path("event_id").asText()).isEqualTo("alarm:handle:" + alarmId + ":" + logId);
            assertThat(frame.path("payload").path("conditionState").asText()).isEqualTo("ACTIVE");

            String eventId = frame.path("event_id").asText();
            String outboxStatus = awaitOutboxStatus(eventId, "DONE", 15000);
            assertThat(outboxStatus).isEqualTo("DONE");
        }
    }

    @Test
    @Order(6)
    void handleLogAndOutboxRollBackTogetherWhenOutboxWriteFails() throws Exception {
        String token = login("handle-admin", "Handle-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        long alarmId = insertAlarm(bound, null, "ACTIVE", "PENDING", LocalDateTime.now(clock).withNano(0), 100.0);
        String constraint = "ck_fail_outbox_13_" + alarmId;
        try {
            jdbc.execute("ALTER TABLE notification_outbox ADD CONSTRAINT " + constraint
                    + " CHECK (aggregate_id <> " + alarmId + ")");
            HttpCall failed = call("POST", "/alarms/" + alarmId + "/handle", token,
                    Map.of("targetStatus", "PROCESSING", "remark", "事务回滚验证"));
            assertThat(failed.status()).as("outbox 写失败应使整个处置事务失败").isGreaterThanOrEqualTo(500);
            assertThat(jdbc.queryForObject("SELECT handle_status FROM alarm WHERE id=?", String.class, alarmId))
                    .isEqualTo("PENDING");
            assertThat(handleLogCount(alarmId)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM notification_outbox
                    WHERE aggregate_id=? AND event_id LIKE 'alarm:handle:%'
                    """, Long.class, alarmId)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE notification_outbox DROP CHECK " + constraint);
        }
        HttpCall retried = call("POST", "/alarms/" + alarmId + "/handle", token,
                Map.of("targetStatus", "PROCESSING", "remark", "故障恢复后重试"));
        assertThat(retried.status()).as(retried.raw()).isEqualTo(200);
        assertThat(handleLogCount(alarmId)).isEqualTo(1L);
    }

    private WsObserver subscribe(String token) throws Exception {
        HttpCall ticket = call("POST", "/auth/ws-ticket", token, null);
        assertThat(ticket.status()).as(ticket.raw()).isEqualTo(200);
        WsObserver observer = new WsObserver();
        observer.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .buildAsync(URI.create("ws://127.0.0.1:" + httpPort + "/ws/monitor?ticket="
                        + ticket.body().path("ticket").asText()), observer)
                .get(10, TimeUnit.SECONDS);
        assertThat(observer.open.await(10, TimeUnit.SECONDS)).isTrue();
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

    private String awaitOutboxStatus(String eventId, String expected, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        String status = null;
        while (System.nanoTime() < deadline) {
            List<String> rows = jdbc.queryForList(
                    "SELECT status FROM notification_outbox WHERE event_id=?", String.class, eventId);
            if (!rows.isEmpty()) {
                status = rows.getFirst();
                if (expected.equals(status)) {
                    return status;
                }
            }
            Thread.sleep(200);
        }
        return status;
    }

    private void applyPoint(BoundTerminal bound, LocalDateTime time, int rawSpeed) {
        participant.apply(bound.vehicleId(), bound.terminalId(), bound.fleetId(), 1,
                new PositionReport(0, 3, 39.908, 116.48, 10, rawSpeed, 90, time, List.of()),
                LocalDateTime.now(clock));
    }

    private long handleLogCount(long alarmId) {
        return jdbc.queryForObject("SELECT count(*) FROM alarm_handle_log WHERE alarm_id=? AND change_type='HANDLE'",
                Long.class, alarmId);
    }

    private List<Long> ids(JsonNode rows) {
        List<Long> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.path("id").asLong()));
        return ids;
    }

    private JsonNode find(JsonNode rows, long alarmId) {
        for (JsonNode row : rows) {
            if (row.path("id").asLong() == alarmId) {
                return row;
            }
        }
        throw new AssertionError("未找到报警 " + alarmId);
    }

    private long insertAlarm(BoundTerminal bound, Long driverId, String conditionState, String handleStatus,
                             LocalDateTime start, double speed) {
        Map<String, Object> rule = jdbc.queryForMap(
                "SELECT id, version FROM alarm_rule WHERE alarm_type = 'OVERSPEED' AND scope_type = 'GLOBAL'");
        long ruleId = ((Number) rule.get("id")).longValue();
        long ruleVersion = ((Number) rule.get("version")).longValue();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ruleId", ruleId);
        snapshot.put("scopeType", "GLOBAL");
        snapshot.put("fleetId", null);
        snapshot.put("alarmType", "OVERSPEED");
        snapshot.put("thresholdKph", 80);
        snapshot.put("ruleVersion", ruleVersion);
        String snapshotJson = json.writeValueAsString(snapshot);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO alarm (vehicle_id, terminal_id, driver_id, fleet_id_at_event, source, alarm_type,
                        business_dimension, condition_state, handle_status, rule_id, rule_version, rule_snapshot,
                        start_time, last_time, created_at, updated_at, recovered_at)
                    VALUES (?, ?, ?, ?, 'PLATFORM', 'OVERSPEED', '', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, bound.vehicleId());
            ps.setLong(2, bound.terminalId());
            if (driverId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, driverId);
            }
            ps.setLong(4, bound.fleetId());
            ps.setString(5, conditionState);
            ps.setString(6, handleStatus);
            ps.setLong(7, ruleId);
            ps.setLong(8, ruleVersion);
            ps.setString(9, snapshotJson);
            ps.setObject(10, start);
            ps.setObject(11, start);
            ps.setObject(12, start);
            ps.setObject(13, start);
            ps.setObject(14, "RECOVERED".equals(conditionState) ? start : null);
            return ps;
        }, keys);
        long alarmId = keys.getKey().longValue();
        jdbc.update("""
                INSERT INTO track_point (terminal_id, vehicle_id, fleet_id_at_event, gps_time, received_at,
                    lng, lat, speed, direction, alarm_flag, status_flag, locate_state, report_type,
                    payload_hash, raw_body)
                VALUES (?, ?, ?, ?, ?, 116.480000, 39.908000, ?, 90, 0, 3, 'VALID', 'REALTIME', ?, ?)
                """, bound.terminalId(), bound.vehicleId(), bound.fleetId(), start, start, speed,
                UUID.randomUUID().toString().replace("-", "") + "a".repeat(32), new byte[] {1, 2});
        return alarmId;
    }

    private long createDriver(String token, long fleetId, String phone) throws Exception {
        int sequence = SEQUENCE.getAndIncrement();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return create(token, "/drivers", Map.of("driverNo", "D" + suffix, "name", "司机" + sequence,
                "phone", phone, "fleetId", fleetId));
    }

    private void bindDriver(String token, long vehicleId, long driverId) throws Exception {
        assertThat(status("PUT", "/vehicles/" + vehicleId + "/driver", token,
                Map.of("driverId", driverId))).isEqualTo(200);
    }

    private BoundTerminal boundTerminal(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String terminalNo = "013800139000" + String.format("%08d", SEQUENCE.getAndIncrement());
        long fleet = create(token, "/fleets", Map.of("fleetCode", "A" + suffix, "fleetName", "报警车队" + suffix));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", "报警" + suffix, "fleetId", fleet));
        long terminal = create(token, "/terminals", Map.of("terminalNo", terminalNo));
        assertThat(status("PUT", "/vehicles/" + vehicle + "/terminal", token,
                Map.of("terminalId", terminal))).isEqualTo(200);
        return new BoundTerminal(terminal, vehicle, fleet, terminalNo, "报警" + suffix);
    }

    private String login(String username, String password) throws Exception {
        HttpCall call = call("POST", "/auth/login", null, Map.of("username", username, "password", password));
        assertThat(call.status()).isEqualTo(200);
        return call.body().path("token").asText();
    }

    private long create(String token, String path, Object body) throws Exception {
        HttpCall call = call("POST", path, token, body);
        assertThat(call.status()).as("创建 %s 失败：%s", path, call.raw()).isEqualTo(201);
        return call.body().path("id").asLong();
    }

    private JsonNode get(String token, String path) throws Exception {
        HttpCall call = call("GET", path, token, null);
        assertThat(call.status()).as("GET %s 失败：%s", path, call.raw()).isEqualTo(200);
        return call.body();
    }

    private int status(String method, String path, String token, Object body) throws Exception {
        return call(method, path, token, body).status();
    }

    private HttpCall call(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + httpPort + "/api/v1" + path))
                .timeout(Duration.ofSeconds(15));
        if (token != null) {
            builder.header("satoken", token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),
                    StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode node = response.body() == null || response.body().isBlank() ? null : json.readTree(response.body());
        return new HttpCall(response.statusCode(), node, response.body());
    }

    record HttpCall(int status, JsonNode body, String raw) { }

    record BoundTerminal(long terminalId, long vehicleId, long fleetId, String terminalNo, String plateNo) { }

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

    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock alarmHandlingClock() {
            return new MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneId.of("Asia/Shanghai"));
        }
    }
}
