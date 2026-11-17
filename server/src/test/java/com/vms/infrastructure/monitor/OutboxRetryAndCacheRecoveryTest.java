package com.vms.infrastructure.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vms.access.TerminalAccessPort;
import com.vms.protocol.Frame;
import com.vms.protocol.FrameCodec;
import com.vms.protocol.MessageHeader;
import com.vms.protocol.BodyProperties;
import com.vms.protocol.PositionReport;
import com.vms.protocol.TerminalAuthentication;
import com.vms.protocol.TerminalGeneralResponse;
import com.vms.protocol.TerminalRegister;
import com.vms.protocol.TerminalRegisterResponse;
import com.vms.support.IntegrationContainers;
import com.vms.support.MutableClock;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
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
import java.util.function.BiPredicate;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 18：outbox 事件唯一与合并、重试上限与 DEAD 可见可重投、崩溃恢复不重复业务事实、
 * Redis 重建只恢复派生位置缓存且不伪造在线，以及故障注入下 ACK 不依赖缓存。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "vms.bootstrap-admin.username=outbox-admin",
        "vms.bootstrap-admin.password=Outbox-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0",
        "vms.monitor.publish-interval-ms=3600000",
        "vms.monitor.dispatch-initial-delay-ms=3600000",
        "vms.monitor.cache-initial-delay-ms=3600000",
        "vms.monitor.cleanup-interval-ms=3600000",
        "vms.monitor.cleanup-initial-delay-ms=3600000"
})
@Import({IntegrationContainers.class, OutboxRetryAndCacheRecoveryTest.TimeConfiguration.class})
@AutoConfigureMockMvc
class OutboxRetryAndCacheRecoveryTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TerminalAccessPort access;
    @Autowired StringRedisTemplate redis;
    @Autowired GenericContainer<?> redisContainer;
    @Autowired MutableClock clock;
    @Autowired PositionOutboxWorker worker;
    @LocalServerPort int httpPort;

    @BeforeEach
    void clearOutbox() {
        jdbc.update("DELETE FROM notification_outbox");
        jdbc.update("DELETE FROM audit_log");
    }

    @Test
    void duplicatePositionDatagramDoesNotDuplicateOutboxEventAndNewerVersionMerges() throws Exception {
        login("outbox-admin", "Outbox-Pass-2026");
        Bound bound = boundTerminal();
        try (TerminalClient terminal = authenticated(bound)) {
            // 业务时钟冻结；回溯 5 秒保证首包与后续包 ageMillis 都落在 [0,60s] 的实时窗口内。
            LocalDateTime gpsTime = LocalDateTime.now(clock).withNano(0).minusSeconds(5);
            byte[] frame = position(gpsTime, 116.31).encode();

            terminal.send(0x0200, 3, frame);
            assertThat(TerminalGeneralResponse.parse(terminal.receive().body()).result()).isZero();
            terminal.send(0x0200, 3, frame);
            assertThat(TerminalGeneralResponse.parse(terminal.receive().body()).result()).isZero();

            assertThat(count("track_point", bound.vehicleId())).isEqualTo(1);
            assertThat(outboxCount()).isEqualTo(1);
            assertThat(outboxLong("position:" + bound.vehicleId(), "version")).isEqualTo(1);

            terminal.send(0x0200, 4, position(gpsTime.plusSeconds(1), 116.32).encode());
            assertThat(TerminalGeneralResponse.parse(terminal.receive().body()).result()).isZero();
            assertThat(count("track_point", bound.vehicleId())).isEqualTo(2);
            assertThat(outboxCount()).isEqualTo(1);
            assertThat(outboxLong("position:" + bound.vehicleId(), "version")).isEqualTo(2);
            assertThat(outboxString("position:" + bound.vehicleId(), "status")).isEqualTo("PENDING");
        }
    }

    @Test
    void workerOnlyClearsTheVersionItReadEvenWhenHigherVersionArrivesDuringPublish() {
        LocalDateTime now = LocalDateTime.now(clock);
        insertOutbox("position:101", "VEHICLE_POSITION", 101, 1, "PENDING", 0, now);
        MonitorWebSocketHandler handler = fakeHandler((eventId, alarmId) -> true, (vehicleId, version) -> {
            jdbc.update("""
                    UPDATE notification_outbox SET version = 2, status = 'PENDING', retry_at = ?, updated_at = ?
                    WHERE event_id = ?
                    """, now, now, "position:" + vehicleId);
            return true;
        });

        manualWorker(handler).dispatch();

        assertThat(outboxLong("position:101", "version")).isEqualTo(2);
        assertThat(outboxString("position:101", "status")).isEqualTo("PENDING");
        assertThat(outboxLong("position:101", "attempts")).isZero();
    }

    @Test
    void alarmAndHandleEventsStayIndependentAndAreNotLostOrCoalesced() {
        LocalDateTime now = LocalDateTime.now(clock);
        insertOutbox("alarm:created:77", "ALARM_EVENT", 77, 1, "PENDING", 0, now);
        insertOutbox("alarm:handle:77:9", "ALARM_EVENT", 77, 1, "PENDING", 0, now);
        insertOutbox("position:77", "VEHICLE_POSITION", 77, 1, "PENDING", 0, now);
        List<String> alarmDeliveries = new ArrayList<>();
        MonitorWebSocketHandler handler = fakeHandler((eventId, alarmId) -> {
            alarmDeliveries.add(eventId);
            return true;
        }, (vehicleId, version) -> true);

        manualWorker(handler).dispatch();

        assertThat(alarmDeliveries).containsExactlyInAnyOrder("alarm:created:77", "alarm:handle:77:9");
        assertThat(outboxCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE status = 'DONE'", Long.class)).isEqualTo(3);
    }

    @Test
    void permanentFailureStopsAtTenAttemptsAndStaysDead() {
        LocalDateTime now = LocalDateTime.now(clock);
        insertOutbox("position:retry-dead", "VEHICLE_POSITION", 42, 1, "PENDING", 9, now.minusSeconds(30));
        PositionOutboxWorker failing = manualWorker(fakeHandler((eventId, alarmId) -> false,
                (vehicleId, version) -> false));

        failing.dispatch();

        assertThat(outboxString("position:retry-dead", "status")).isEqualTo("DEAD");
        assertThat(outboxLong("position:retry-dead", "attempts")).isEqualTo(10);
        LocalDateTime retryAt = jdbc.queryForObject(
                "SELECT retry_at FROM notification_outbox WHERE event_id = ?", LocalDateTime.class,
                "position:retry-dead");
        assertThat(retryAt).isAfter(LocalDateTime.now(clock).plusSeconds(1));

        clock.advance(Duration.ofMinutes(5));
        failing.dispatch();

        assertThat(outboxString("position:retry-dead", "status")).isEqualTo("DEAD");
        assertThat(outboxLong("position:retry-dead", "attempts")).isEqualTo(10);
    }

    @Test
    void deadEventIsVisibleAndRedeliveryIsAuditedWithoutChangingBusinessFacts() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock);
        insertOutbox("position:4242", "VEHICLE_POSITION", 4242, 5, "DEAD", 10, now.minusMinutes(1));
        String token = login("outbox-admin", "Outbox-Pass-2026");

        mvc.perform(get("/api/v1/audit/operation-records").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outboxCounts.DEAD").value(1))
                .andExpect(jsonPath("$.deadOutbox[0].eventId").value("position:4242"))
                .andExpect(jsonPath("$.deadOutbox[0].attempts").value(10));

        mvc.perform(post("/api/v1/outbox/position:4242/redeliver").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("position:4242"))
                .andExpect(jsonPath("$.eventType").value("VEHICLE_POSITION"))
                .andExpect(jsonPath("$.aggregateId").value(4242))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0));

        assertThat(outboxString("position:4242", "status")).isEqualTo("PENDING");
        assertThat(outboxLong("position:4242", "attempts")).isZero();
        assertThat(outboxString("position:4242", "event_type")).isEqualTo("VEHICLE_POSITION");
        assertThat(outboxLong("position:4242", "aggregate_id")).isEqualTo(4242);
        assertThat(outboxLong("position:4242", "version")).isEqualTo(5);

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT category, action, target, result, detail FROM audit_log
                WHERE target = 'outbox:position:4242'
                """);
        assertThat(audit.get("category")).isEqualTo("OUTBOX");
        assertThat(audit.get("action")).isEqualTo("REDELIVER");
        assertThat(audit.get("result")).isEqualTo("SUCCESS");
        assertThat(String.valueOf(audit.get("detail")))
                .contains("eventType=VEHICLE_POSITION", "fromStatus=DEAD", "toStatus=PENDING")
                .doesNotContain("password", "token");

        manualWorker(fakeHandler((eventId, alarmId) -> true, (vehicleId, version) -> true)).dispatch();
        assertThat(outboxString("position:4242", "status")).isEqualTo("DONE");
    }

    @Test
    void redeliveryRejectsNonDeadUnknownEventsAndNonAdminActors() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock);
        insertOutbox("position:pending-1", "VEHICLE_POSITION", 11, 1, "PENDING", 0, now);
        insertOutbox("position:dead-guard", "VEHICLE_POSITION", 12, 1, "DEAD", 10, now);
        String admin = login("outbox-admin", "Outbox-Pass-2026");

        mvc.perform(post("/api/v1/outbox/position:pending-1/redeliver").header("satoken", admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUTBOX_NOT_DEAD"));
        mvc.perform(post("/api/v1/outbox/position:missing/redeliver").header("satoken", admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OUTBOX_EVENT_NOT_FOUND"));
        Map<String, Object> failureAudit = jdbc.queryForMap("""
                SELECT result, detail FROM audit_log WHERE target = 'outbox:position:pending-1'
                """);
        assertThat(failureAudit.get("result")).isEqualTo("FAILURE");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String monitorName = "outbox-monitor-" + suffix;
        create(admin, "/users", Map.of("username", monitorName, "password", "Monitor-Pass-2026",
                "roleCode", "MONITOR", "fleetIds", List.of()));
        String monitor = login(monitorName, "Monitor-Pass-2026");
        mvc.perform(post("/api/v1/outbox/position:dead-guard/redeliver").header("satoken", monitor))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(outboxString("position:dead-guard", "status")).isEqualTo("DEAD");
    }

    @Test
    void workerRecoveryDoesNotDuplicateAlreadyCommittedBusinessFacts() {
        Bound bound = boundTerminal();
        LocalDateTime gpsTime = LocalDateTime.now(clock).withNano(0);
        jdbc.update("""
                INSERT INTO track_point (terminal_id, vehicle_id, fleet_id_at_event, gps_time, received_at,
                    lng, lat, speed, direction, alarm_flag, status_flag, locate_state, report_type,
                    payload_hash, raw_body)
                VALUES (?, ?, ?, ?, ?, 116.41, 39.91, 10.0, 90, 0, 3, 'VALID', 'REALTIME', ?, ?)
                """, bound.terminalId(), bound.vehicleId(), bound.fleetId(), gpsTime, gpsTime,
                "b".repeat(64), new byte[] {7, 8, 9});
        jdbc.update("""
                INSERT INTO vehicle_last_position
                    (vehicle_id, terminal_id, gps_time, received_at, lng, lat, speed, direction, status_flag, version)
                VALUES (?, ?, ?, ?, 116.41, 39.91, 10.0, 90, 3, 1)
                """, bound.vehicleId(), bound.terminalId(), gpsTime, gpsTime);
        insertOutbox("position:" + bound.vehicleId(), "VEHICLE_POSITION", bound.vehicleId(), 1,
                "PENDING", 0, gpsTime);
        PositionOutboxWorker recovering = manualWorker(fakeHandler((eventId, alarmId) -> true,
                (vehicleId, version) -> true));

        recovering.dispatch();
        assertThat(outboxString("position:" + bound.vehicleId(), "status")).isEqualTo("DONE");
        assertBusinessFactsUnchanged(bound, gpsTime);

        // 模拟 worker 已推送但未落 DONE 即崩溃：事件按原 event_id/version 重投。
        jdbc.update("""
                UPDATE notification_outbox SET status = 'PENDING', attempts = 0, retry_at = ?, updated_at = ?
                WHERE event_id = ?
                """, gpsTime, gpsTime, "position:" + bound.vehicleId());
        recovering.dispatch();

        assertThat(outboxString("position:" + bound.vehicleId(), "status")).isEqualTo("DONE");
        assertBusinessFactsUnchanged(bound, gpsTime);
    }

    private void assertBusinessFactsUnchanged(Bound bound, LocalDateTime gpsTime) {
        assertThat(count("track_point", bound.vehicleId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM vehicle_last_position WHERE vehicle_id = ?", Long.class,
                bound.vehicleId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT gps_time FROM vehicle_last_position WHERE vehicle_id = ?", LocalDateTime.class,
                bound.vehicleId())).isEqualTo(gpsTime);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM track_point WHERE vehicle_id = ? AND gps_time = ?", Long.class,
                bound.vehicleId(), gpsTime)).isEqualTo(1);
    }

    @Test
    void redisRestartRebuildsOnlyDerivedPositionCacheFromCommittedFacts() {
        Bound bound = boundTerminal();
        LocalDateTime gpsTime = LocalDateTime.now(clock).withNano(0);
        insertLastPosition(bound, gpsTime, 116.21, 1);

        worker.refreshCache();
        assertThat(redis.opsForValue().get("vms:position:" + bound.vehicleId())).contains("116.210000");

        // 共享 Redis 容器的随机映射端口在 docker restart 后会重新分配（实测 46425→45205），
        // 重启容器会让应用连接配置与 Sa-Token 会话一并失效；以「清空数据集 + 丢弃现有连接」
        // 等价模拟重启后的空缓存与断线重连，重建数据仍来自真实 Redis 与 MySQL。
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        LettuceConnectionFactory factory = (LettuceConnectionFactory) redis.getConnectionFactory();
        assertThat(factory).isNotNull();
        factory.resetConnection();
        assertThat(redis.hasKey("vms:position:" + bound.vehicleId())).isFalse();

        Awaitility.await().ignoreExceptions().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            worker.refreshCache();
            assertThat(redis.opsForValue().get("vms:position:" + bound.vehicleId()))
                    .contains("116.210000")
                    .contains("\"version\":1");
        });
        var keys = redis.keys("vms:*");
        assertThat(keys).isNotEmpty().allMatch(key -> key.startsWith("vms:position:"));
        assertThat(keys).noneMatch(key -> key.contains("online") || key.contains("connection"));
    }

    @Test
    void staleRedisCacheCannotFabricateConnectionOrOverrideCommittedPosition() throws Exception {
        Bound bound = boundTerminal();
        LocalDateTime gpsTime = LocalDateTime.now(clock).withNano(0);
        jdbc.update("UPDATE terminal SET ever_authenticated = TRUE, last_seen_at = ?, updated_at = ? WHERE id = ?",
                gpsTime, gpsTime, bound.terminalId());
        insertLastPosition(bound, gpsTime, 116.11, 1);
        redis.opsForValue().set("vms:position:" + bound.vehicleId(),
                "{\"vehicle_id\":" + bound.vehicleId() + ",\"version\":99,\"lng\":121.0,\"lat\":31.0}");
        String token = login("outbox-admin", "Outbox-Pass-2026");

        JsonNode vehicle = snapshotVehicle(token, bound.vehicleId());

        assertThat(vehicle.path("communicationStatus").asText()).isEqualTo("OFFLINE");
        assertThat(vehicle.path("position").path("version").asLong()).isEqualTo(1);
        assertThat(vehicle.path("position").path("lng").asDouble()).isEqualTo(116.11);
        assertThat(redis.opsForValue().get("vms:position:" + bound.vehicleId())).contains("\"version\":99");
    }

    @Test
    void positionAckDoesNotDependOnRedisCacheAndRecoveryRebuildsIt() throws Exception {
        Bound bound = boundTerminal();
        login("outbox-admin", "Outbox-Pass-2026");
        try (TerminalClient terminal = authenticated(bound)) {
            var docker = redisContainer.getDockerClient();
            docker.pauseContainerCmd(redisContainer.getContainerId()).exec();
            LocalDateTime gpsTime = LocalDateTime.now(clock).withNano(0);
            long started = System.nanoTime();
            try {
                terminal.send(0x0200, 3, position(gpsTime, 116.51).encode());
                assertThat(TerminalGeneralResponse.parse(terminal.receive().body()).result()).isZero();
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
                assertThat(jdbc.queryForObject(
                        "SELECT version FROM vehicle_last_position WHERE vehicle_id = ?", Long.class,
                        bound.vehicleId())).isEqualTo(1);
                assertThat(outboxString("position:" + bound.vehicleId(), "status")).isIn("PENDING", "RETRY", "DONE");
            } finally {
                docker.unpauseContainerCmd(redisContainer.getContainerId()).exec();
            }
            Awaitility.await().ignoreExceptions().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                worker.refreshCache();
                assertThat(redis.opsForValue().get("vms:position:" + bound.vehicleId())).contains("116.510000");
            });
        }
    }

    private MonitorWebSocketHandler fakeHandler(BiPredicate<String, Long> alarmDelivery,
                                                BiPredicate<Long, Long> positionDelivery) {
        return new MonitorWebSocketHandler(null, null, null, null, json, clock, 1, 0) {
            @Override
            public boolean publish(long vehicleId, long version) {
                return positionDelivery.test(vehicleId, version);
            }

            @Override
            public boolean publishAlarm(String eventId, long alarmId) {
                return alarmDelivery.test(eventId, alarmId);
            }
        };
    }

    private PositionOutboxWorker manualWorker(MonitorWebSocketHandler handler) {
        return new PositionOutboxWorker(jdbc, redis, json, handler, clock);
    }

    private void insertOutbox(String eventId, String eventType, long aggregateId, long version,
                              String status, int attempts, LocalDateTime retryAt) {
        jdbc.update("""
                INSERT INTO notification_outbox
                    (event_id, event_type, aggregate_id, version, status, retry_at, attempts, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, eventId, eventType, aggregateId, version, status, retryAt, attempts, retryAt);
    }

    private void insertLastPosition(Bound bound, LocalDateTime gpsTime, double longitude, long version) {
        jdbc.update("""
                INSERT INTO vehicle_last_position
                    (vehicle_id, terminal_id, gps_time, received_at, lng, lat, speed, direction, status_flag, version)
                VALUES (?, ?, ?, ?, ?, 39.91, 10.0, 90, 3, ?)
                """, bound.vehicleId(), bound.terminalId(), gpsTime, gpsTime, longitude, version);
    }

    private long outboxLong(String eventId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM notification_outbox WHERE event_id = ?",
                Long.class, eventId);
    }

    private String outboxString(String eventId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM notification_outbox WHERE event_id = ?",
                String.class, eventId);
    }

    private long outboxCount() {
        return jdbc.queryForObject("SELECT count(*) FROM notification_outbox", Long.class);
    }

    private long count(String table, long vehicleId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE vehicle_id = ?",
                Long.class, vehicleId);
    }

    private JsonNode snapshotVehicle(String token, long vehicleId) throws Exception {
        JsonNode snapshot = json.readTree(mvc.perform(get("/api/v1/monitor/snapshot").header("satoken", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        for (JsonNode vehicle : snapshot.path("vehicles")) {
            if (vehicle.path("vehicleId").asLong() == vehicleId) {
                return vehicle;
            }
        }
        throw new AssertionError("Snapshot is missing vehicle " + vehicleId);
    }

    private PositionReport position(LocalDateTime time, double longitude) {
        return new PositionReport(0, 3, 39.908, longitude, 10, 600, 90, time, List.of());
    }

    private Bound boundTerminal() {
        try {
            String token = login("outbox-admin", "Outbox-Pass-2026");
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            String terminalNo = "013800139001" + String.format("%08d", SEQUENCE.getAndIncrement());
            long fleet = create(token, "/fleets", Map.of("fleetCode", "O" + suffix, "fleetName", "通知车队" + suffix));
            long vehicle = create(token, "/vehicles", Map.of("plateNo", "通知" + suffix, "fleetId", fleet));
            long terminal = create(token, "/terminals", Map.of("terminalNo", terminalNo));
            mvc.perform(put("/api/v1/vehicles/" + vehicle + "/terminal").header("satoken", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("terminalId", terminal))))
                    .andExpect(status().isOk());
            // 09 起按 gps_time 归属历史区间；绑定起点回溯，确保待上报点落在区间内。
            LocalDateTime bindingSince = LocalDateTime.now(clock).minusHours(1);
            jdbc.update("UPDATE terminal_vehicle_binding SET effective_from = ? WHERE terminal_id = ? AND effective_to IS NULL",
                    bindingSince, terminal);
            jdbc.update("UPDATE vehicle_fleet_history SET effective_from = ? WHERE vehicle_id = ? AND effective_to IS NULL",
                    bindingSince, vehicle);
            return new Bound(terminal, vehicle, fleet, terminalNo);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private TerminalClient authenticated(Bound bound) throws Exception {
        TerminalClient client = new TerminalClient(access.port(), bound.terminalNo());
        client.send(0x0100, 1, new TerminalRegister(1, 1, "ABC12", "TESTMODEL",
                bound.terminalNo(), 2, "模拟车辆").encode());
        TerminalRegisterResponse registration = TerminalRegisterResponse.parse(client.receive().body());
        assertThat(registration.result()).isZero();
        client.send(0x0102, 2, new TerminalAuthentication(registration.authCode(), "012345678901234",
                "00000000000000000001").encode());
        assertThat(TerminalGeneralResponse.parse(client.receive().body()).result()).isZero();
        return client;
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

    private record Bound(long terminalId, long vehicleId, long fleetId, String terminalNo) { }

    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock outboxClock() {
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

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }
}