package com.vms.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "vms.bootstrap-admin.username=batch-admin",
        "vms.bootstrap-admin.password=Batch-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0"
})
@Import({IntegrationContainers.class, BatchBackfillFlowTest.TimeConfiguration.class})
@AutoConfigureMockMvc
class BatchBackfillFlowTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);
    private static final String ADMIN = "batch-admin";
    private static final String ADMIN_PASSWORD = "Batch-Pass-2026";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TerminalAccessPort access;
    @Autowired MutableClock clock;

    @Test
    void validBatchStoresEveryPositionAndAcknowledgesOnce() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        BoundTerminal bound = boundTerminal(token);
        clock.advance(Duration.ofSeconds(120));
        LocalDateTime base = LocalDateTime.now(clock).withNano(0);
        PositionReport first = position(base.minusSeconds(70), 116.31);
        PositionReport second = position(base.minusSeconds(69), 116.32);
        try (TerminalClient terminal = authenticated(bound)) {
            terminal.send(0x0704, 7, batch(PositionBatch.TYPE_NORMAL, first, second));
            TerminalGeneralResponse ack = TerminalGeneralResponse.parse(terminal.receive().body());
            assertThat(ack.responseId()).isEqualTo(0x0704);
            assertThat(ack.responseSerial()).isEqualTo(7);
            assertThat(ack.result()).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE terminal_id = ?", Long.class,
                bound.terminalId())).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT report_type FROM track_point WHERE vehicle_id = ? ORDER BY gps_time
                """, String.class, bound.vehicleId())).containsExactly("HISTORY", "HISTORY");
        assertThat(jdbc.queryForList("SELECT DISTINCT fleet_id_at_event FROM track_point WHERE vehicle_id = ?",
                Long.class, bound.vehicleId())).containsExactly(bound.fleetId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_last_position WHERE vehicle_id = ?",
                Long.class, bound.vehicleId())).isZero();
    }

    @Test
    void mixedNewDuplicateAndConflictBatchExplainsEveryItemWithoutExtraRows() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        BoundTerminal bound = boundTerminal(token);
        clock.advance(Duration.ofSeconds(120));
        LocalDateTime gpsTime = LocalDateTime.now(clock).withNano(0).minusSeconds(70);
        PositionReport kept = position(gpsTime, 116.40);
        PositionReport conflicting = position(gpsTime, 116.41);
        try (TerminalClient terminal = authenticated(bound)) {
            terminal.send(0x0704, 9, batch(PositionBatch.TYPE_NORMAL, kept, kept, conflicting));
            TerminalGeneralResponse ack = TerminalGeneralResponse.parse(terminal.receive().body());
            assertThat(ack.responseId()).isEqualTo(0x0704);
            assertThat(ack.responseSerial()).isEqualTo(9);
            assertThat(ack.result()).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE terminal_id = ?", Long.class,
                bound.terminalId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT lng FROM track_point WHERE terminal_id = ? AND gps_time = ?",
                java.math.BigDecimal.class, bound.terminalId(), gpsTime)).isEqualByComparingTo("116.40");
        assertThat(jdbc.queryForList("SELECT reason FROM telemetry_anomaly WHERE terminal_id = ?", String.class,
                bound.terminalId())).containsExactly("CONFLICTING_CONTENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE aggregate_id = ?",
                Long.class, bound.vehicleId())).isZero();
    }

    @Test
    void structuralFailureRejectsWholeBatchWithoutWritesOrSuccessAck() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        BoundTerminal bound = boundTerminal(token);
        clock.advance(Duration.ofSeconds(120));
        LocalDateTime base = LocalDateTime.now(clock).withNano(0);
        List<PositionReport> twentyOne = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            twentyOne.add(position(base.minusSeconds(100 - index), 116.50 + index / 1000.0));
        }
        try (TerminalClient terminal = authenticated(bound)) {
            terminal.send(0x0704, 11, rawBatch(21, twentyOne));
            assertThatThrownBy(terminal::receive)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TCP closed");
        }
        try (TerminalClient terminal = authenticated(bound)) {
            // 声明 2 项但只带 1 项：整包结构不完整，必须整批拒绝而不是保存首项。
            terminal.send(0x0704, 12, rawBatch(2, List.of(position(base.minusSeconds(80), 116.55))));
            assertThatThrownBy(terminal::receive)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TCP closed");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE terminal_id = ?", Long.class,
                bound.terminalId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM telemetry_anomaly WHERE terminal_id = ?", Long.class,
                bound.terminalId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_last_position WHERE vehicle_id = ?",
                Long.class, bound.vehicleId())).isZero();
    }

    @Test
    void databaseFailureMidBatchRollsBackEveryItemAndReturnsNoSuccessAck() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        BoundTerminal bound = boundTerminal(token);
        clock.advance(Duration.ofSeconds(120));
        LocalDateTime base = LocalDateTime.now(clock).withNano(0);
        LocalDateTime failingTime = base.minusSeconds(69);
        String constraint = "ck_batch_fail_" + SEQUENCE.getAndIncrement();
        String literal = failingTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        jdbc.execute("ALTER TABLE track_point ADD CONSTRAINT " + constraint
                + " CHECK (gps_time <> '" + literal + "')");
        try (TerminalClient terminal = authenticated(bound)) {
            terminal.send(0x0704, 13, batch(PositionBatch.TYPE_NORMAL,
                    position(base.minusSeconds(70), 116.60), position(failingTime, 116.61)));
            assertThatThrownBy(terminal::receive)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TCP closed");
        } finally {
            jdbc.execute("ALTER TABLE track_point DROP CHECK " + constraint);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE terminal_id = ?", Long.class,
                bound.terminalId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE aggregate_id = ?",
                Long.class, bound.vehicleId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_last_position WHERE vehicle_id = ?",
                Long.class, bound.vehicleId())).isZero();
    }

    @Test
    void backfillAfterTerminalRebindBelongsToOldVehicleAndFleet() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleetOld = createFleet(token, "O" + suffix, "旧队" + suffix);
        long fleetNew = createFleet(token, "N" + suffix, "新队" + suffix);
        long oldVehicle = createVehicle(token, "旧车" + suffix, fleetOld);
        long newVehicle = createVehicle(token, "新车" + suffix, fleetNew);
        long terminalId = createTerminal(token, terminalNo());
        bind(token, oldVehicle, terminalId);
        clock.advance(Duration.ofSeconds(300));
        LocalDateTime rebindBoundary = LocalDateTime.now(clock).withNano(0);
        bind(token, oldVehicle, null);
        clock.advance(Duration.ofSeconds(2));
        bind(token, newVehicle, terminalId);
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        BoundTerminal bound = new BoundTerminal(terminalId, oldVehicle, fleetOld, terminalNoOf(terminalId));
        try (TerminalClient terminal = authenticated(bound)) {
            terminal.send(0x0704, 15, batch(PositionBatch.TYPE_NORMAL,
                    position(rebindBoundary.minusSeconds(60), 116.70),
                    position(now, 116.71),
                    position(rebindBoundary.minusSeconds(360), 116.72)));
            TerminalGeneralResponse ack = TerminalGeneralResponse.parse(terminal.receive().body());
            assertThat(ack.responseId()).isEqualTo(0x0704);
            assertThat(ack.result()).isZero();
        }
        var old = jdbc.queryForMap("""
                SELECT vehicle_id, fleet_id_at_event, report_type FROM track_point
                WHERE terminal_id = ? AND gps_time = ?
                """, terminalId, rebindBoundary.minusSeconds(60));
        assertThat(((Number) old.get("vehicle_id")).longValue()).isEqualTo(oldVehicle);
        assertThat(((Number) old.get("fleet_id_at_event")).longValue()).isEqualTo(fleetOld);
        assertThat(old.get("report_type")).isEqualTo("HISTORY");
        var fresh = jdbc.queryForMap("""
                SELECT vehicle_id, fleet_id_at_event, report_type FROM track_point
                WHERE terminal_id = ? AND gps_time = ?
                """, terminalId, now);
        assertThat(((Number) fresh.get("vehicle_id")).longValue()).isEqualTo(newVehicle);
        assertThat(((Number) fresh.get("fleet_id_at_event")).longValue()).isEqualTo(fleetNew);
        assertThat(fresh.get("report_type")).isEqualTo("REALTIME");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE terminal_id = ?", Long.class,
                terminalId)).isEqualTo(2);
        var unassignable = jdbc.queryForMap("""
                SELECT vehicle_id, fleet_id_at_event, reason FROM telemetry_anomaly
                WHERE terminal_id = ? AND gps_time = ?
                """, terminalId, rebindBoundary.minusSeconds(360));
        assertThat(unassignable.get("reason")).isEqualTo("UNASSIGNABLE");
        assertThat(unassignable.get("vehicle_id")).isNull();
        assertThat(unassignable.get("fleet_id_at_event")).isNull();
        assertThat(jdbc.queryForObject("SELECT vehicle_id FROM vehicle_last_position WHERE vehicle_id = ?",
                Long.class, newVehicle)).isEqualTo(newVehicle);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_last_position WHERE vehicle_id = ?",
                Long.class, oldVehicle)).isZero();
    }

    @Test
    void fleetTransferKeepsEventFleetAndScopesHistoryAndSnapshotByRole() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleetOld = createFleet(token, "T" + suffix, "转出队" + suffix);
        long fleetNew = createFleet(token, "R" + suffix, "转入队" + suffix);
        long vehicle = createVehicle(token, "转队" + suffix, fleetOld);
        String terminalNo = terminalNo();
        long terminalId = createTerminal(token, terminalNo);
        bind(token, vehicle, terminalId);
        clock.advance(Duration.ofSeconds(300));
        LocalDateTime transferBoundary = LocalDateTime.now(clock).withNano(0);
        transfer(token, vehicle, fleetNew);
        clock.advance(Duration.ofSeconds(2));
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        BoundTerminal bound = new BoundTerminal(terminalId, vehicle, fleetNew, terminalNo);
        try (TerminalClient terminal = authenticated(bound)) {
            terminal.send(0x0704, 17, batch(PositionBatch.TYPE_NORMAL,
                    position(transferBoundary.minusSeconds(60), 116.80),
                    position(now, 116.81)));
            assertThat(TerminalGeneralResponse.parse(terminal.receive().body()).result()).isZero();
        }
        assertThat(jdbc.queryForList("""
                SELECT fleet_id_at_event FROM track_point WHERE vehicle_id = ? ORDER BY gps_time
                """, Long.class, vehicle)).containsExactly(fleetOld, fleetNew);
        assertThat(jdbc.queryForObject("SELECT fleet_id_at_event FROM track_point WHERE vehicle_id = ? AND gps_time = ?",
                Long.class, vehicle, transferBoundary.minusSeconds(60))).isEqualTo(fleetOld);

        String monitor = login(createUser(token, "monitor-" + suffix, "MONITOR", List.of()), "User-Pass-2026");
        String leaderOld = login(createUser(token, "old-" + suffix, "FLEET_LEADER", List.of(fleetOld)),
                "User-Pass-2026");
        String leaderNew = login(createUser(token, "new-" + suffix, "FLEET_LEADER", List.of(fleetNew)),
                "User-Pass-2026");

        JsonNode newHistory = body(mvc.perform(get("/api/v1/vehicles/" + vehicle + "/fleet-history")
                .header("satoken", leaderNew)).andExpect(status().isOk()).andReturn());
        assertThat(newHistory).hasSize(1);
        assertThat(newHistory.get(0).path("fleetId").asLong()).isEqualTo(fleetNew);
        assertThat(fleetIds(newHistory)).doesNotContain(fleetOld);

        JsonNode allHistory = body(mvc.perform(get("/api/v1/vehicles/" + vehicle + "/fleet-history")
                .header("satoken", monitor)).andExpect(status().isOk()).andReturn());
        assertThat(fleetIds(allHistory)).containsExactly(fleetOld, fleetNew);

        mvc.perform(get("/api/v1/vehicles/" + vehicle + "/fleet-history").header("satoken", leaderOld))
                .andExpect(status().isForbidden());

        JsonNode newSnapshot = body(mvc.perform(get("/api/v1/monitor/snapshot").header("satoken", leaderNew))
                .andExpect(status().isOk()).andReturn());
        JsonNode transferred = vehicle(newSnapshot, vehicle);
        assertThat(transferred).isNotNull();
        assertThat(transferred.path("position").path("lng").asDouble()).isEqualTo(116.81);
        assertThat(transferred.path("position").path("gpsTime").asText()).contains("+08:00");

        JsonNode oldSnapshot = body(mvc.perform(get("/api/v1/monitor/snapshot").header("satoken", leaderOld))
                .andExpect(status().isOk()).andReturn());
        assertThat(vehicle(oldSnapshot, vehicle)).isNull();
    }

    @Test
    void backfillBatchNeverRollsBackLatestPositionOrRealtimeState() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        BoundTerminal bound = boundTerminal(token);
        clock.advance(Duration.ofSeconds(120));
        LocalDateTime base = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            terminal.send(0x0200, 21, position(base, 116.90).encode());
            assertThat(TerminalGeneralResponse.parse(terminal.receive().body()).result()).isZero();
            terminal.send(0x0704, 22, batch(PositionBatch.TYPE_NORMAL,
                    position(base.minusSeconds(10), 116.91), position(base.minusSeconds(5), 116.92)));
            assertThat(TerminalGeneralResponse.parse(terminal.receive().body()).result()).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT version FROM vehicle_last_position WHERE vehicle_id = ?",
                Long.class, bound.vehicleId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT lng FROM vehicle_last_position WHERE vehicle_id = ?",
                java.math.BigDecimal.class, bound.vehicleId())).isEqualByComparingTo("116.90");
        assertThat(jdbc.queryForObject("SELECT gps_time FROM vehicle_last_position WHERE vehicle_id = ?",
                LocalDateTime.class, bound.vehicleId())).isEqualTo(base);
        assertThat(jdbc.queryForObject("SELECT version FROM vehicle_monitor_state WHERE vehicle_id = ?",
                Long.class, bound.vehicleId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM notification_outbox WHERE aggregate_id = ?",
                Long.class, bound.vehicleId())).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT report_type FROM track_point WHERE vehicle_id = ? ORDER BY gps_time",
                String.class, bound.vehicleId())).containsExactly("HISTORY", "HISTORY", "REALTIME");
    }

    private PositionReport position(LocalDateTime time, double longitude) {
        return new PositionReport(0, 3, 39.908, longitude, 10, 600, 90, time, List.of());
    }

    private byte[] batch(int dataType, PositionReport... items) {
        return new PositionBatch(dataType, List.of(items)).encode();
    }

    private byte[] rawBatch(int declaredCount, List<PositionReport> items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(declaredCount >>> 8);
        out.write(declaredCount);
        out.write(PositionBatch.TYPE_NORMAL);
        for (PositionReport item : items) {
            byte[] encoded = item.encode();
            out.write(encoded.length >>> 8);
            out.write(encoded.length);
            out.writeBytes(encoded);
        }
        return out.toByteArray();
    }

    private String login(String username, String password) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .path("token").asText();
    }

    private BoundTerminal boundTerminal(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String terminalNo = terminalNo();
        long fleet = createFleet(token, "B" + suffix, "批量" + suffix);
        long vehicle = createVehicle(token, "批量" + suffix, fleet);
        long terminal = createTerminal(token, terminalNo);
        bind(token, vehicle, terminal);
        return new BoundTerminal(terminal, vehicle, fleet, terminalNo);
    }

    private String terminalNo() {
        return "013800140000" + String.format("%08d", SEQUENCE.getAndIncrement());
    }

    private String terminalNoOf(long terminalId) {
        return jdbc.queryForObject("SELECT terminal_no FROM terminal WHERE id = ?", String.class, terminalId);
    }

    private long createFleet(String token, String code, String name) throws Exception {
        return create(token, "/fleets", Map.of("fleetCode", code, "fleetName", name));
    }

    private long createVehicle(String token, String plateNo, long fleetId) throws Exception {
        return create(token, "/vehicles", Map.of("plateNo", plateNo, "fleetId", fleetId));
    }

    private long createTerminal(String token, String terminalNo) throws Exception {
        return create(token, "/terminals", Map.of("terminalNo", terminalNo));
    }

    private void bind(String token, long vehicleId, Long terminalId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("terminalId", terminalId);
        mvc.perform(put("/api/v1/vehicles/" + vehicleId + "/terminal").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private void transfer(String token, long vehicleId, long fleetId) throws Exception {
        mvc.perform(put("/api/v1/vehicles/" + vehicleId + "/fleet").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fleetId", fleetId))))
                .andExpect(status().isOk());
    }

    private String createUser(String token, String username, String role, List<Long> fleetIds) throws Exception {
        mvc.perform(post("/api/v1/users").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", "User-Pass-2026",
                                "roleCode", role, "fleetIds", fleetIds))))
                .andExpect(status().isCreated());
        return username;
    }

    private long create(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1" + path).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("id").asLong();
    }

    private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private List<Long> fleetIds(JsonNode history) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode entry : history) {
            ids.add(entry.path("fleetId").asLong());
        }
        return ids;
    }

    private JsonNode vehicle(JsonNode snapshot, long vehicleId) {
        for (JsonNode candidate : snapshot.path("vehicles")) {
            if (candidate.path("vehicleId").asLong() == vehicleId) {
                return candidate;
            }
        }
        return null;
    }

    private TerminalClient authenticated(BoundTerminal bound) throws Exception {
        TerminalClient client = new TerminalClient(access.port(), bound.terminalNo());
        client.send(0x0100, 1, new TerminalRegister(1, 1, "ABC12", "TESTMODEL", bound.terminalNo(), 2,
                "模拟车辆").encode());
        TerminalRegisterResponse registration = TerminalRegisterResponse.parse(client.receive().body());
        assertThat(registration.result()).isZero();
        client.send(0x0102, 2, new TerminalAuthentication(registration.authCode(),
                "012345678901234", "00000000000000000001").encode());
        assertThat(TerminalGeneralResponse.parse(client.receive().body()).result()).isZero();
        return client;
    }

    private record BoundTerminal(long terminalId, long vehicleId, long fleetId, String terminalNo) { }

    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock batchClock() {
            return new MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS),
                    ZoneId.of("Asia/Shanghai"));
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
