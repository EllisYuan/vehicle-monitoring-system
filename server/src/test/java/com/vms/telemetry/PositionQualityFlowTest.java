package com.vms.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vms.access.TerminalAccessPort;
import com.vms.protocol.Bcd;
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
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
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
        "vms.bootstrap-admin.username=quality-admin",
        "vms.bootstrap-admin.password=Quality-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0"
})
@Import({IntegrationContainers.class, PositionQualityFlowTest.TimeConfiguration.class})
@AutoConfigureMockMvc
class PositionQualityFlowTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TerminalAccessPort access;
    @Autowired MutableClock clock;
    @LocalServerPort int httpPort;

    @Test
    void retransmissionWithChangedSerialCreatesOnePositionAndReturnsSuccess() throws Exception {
        String token = login("quality-admin", "Quality-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime gpsTime = LocalDateTime.now(clock).withNano(0);
        PositionReport report = position(gpsTime, 116.31);
        try (TerminalClient terminal = authenticated(bound)) {
            terminal.send(0x0200, 3, report.encode());
            TerminalGeneralResponse first = TerminalGeneralResponse.parse(terminal.receive().body());
            assertThat(first.responseId()).isEqualTo(0x0200);
            assertThat(first.responseSerial()).isEqualTo(3);
            assertThat(first.result()).isZero();

            terminal.send(0x0200, 4, report.encode());
            TerminalGeneralResponse retransmission = TerminalGeneralResponse.parse(terminal.receive().body());
            assertThat(retransmission.responseId()).isEqualTo(0x0200);
            assertThat(retransmission.responseSerial()).isEqualTo(4);
            assertThat(retransmission.result()).isZero();

            assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE vehicle_id = ?", Long.class,
                    bound.vehicleId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT version FROM vehicle_last_position WHERE vehicle_id = ?",
                    Long.class, bound.vehicleId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT version FROM notification_outbox WHERE aggregate_id = ?",
                    Long.class, bound.vehicleId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM telemetry_anomaly WHERE vehicle_id = ?",
                    Long.class, bound.vehicleId())).isZero();
        }
    }

    @Test
    void staleAndOldPointsOnlyBackfillHistoryAndNeverRollBackSnapshot() throws Exception {
        String token = login("quality-admin", "Quality-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        // 08 原始用例假设所有点都归属当前绑定；09 起按 gps_time 解析历史区间，
        // 先推进时钟使绑定生效早于待补历史点，保持“旧点只补历史”的原意。
        clock.advance(java.time.Duration.ofSeconds(120));
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 10, position(now.minusSeconds(60), 116.30));
            assertThat(lastPositionVersion(bound)).isEqualTo(1);
            assertThat(lastPositionLng(bound)).isEqualByComparingTo("116.30");

            sendAndExpectSuccess(terminal, 11, position(now.minusSeconds(61), 116.31));
            assertThat(trackTypeAt(bound, now.minusSeconds(61))).isEqualTo("HISTORY");
            assertThat(lastPositionVersion(bound)).isEqualTo(1);
            assertThat(lastPositionLng(bound)).isEqualByComparingTo("116.30");

            sendAndExpectSuccess(terminal, 12, position(now, 116.32));
            assertThat(lastPositionVersion(bound)).isEqualTo(2);
            assertThat(lastPositionLng(bound)).isEqualByComparingTo("116.32");

            sendAndExpectSuccess(terminal, 13, position(now.minusSeconds(10), 116.33));
            assertThat(trackTypeAt(bound, now.minusSeconds(10))).isEqualTo("HISTORY");
            assertThat(lastPositionVersion(bound)).isEqualTo(2);
            assertThat(lastPositionLng(bound)).isEqualByComparingTo("116.32");
        }
    }

    @Test
    void futureTolerancePointStaysHistoryAndDoesNotFreezeRealtime() throws Exception {
        String token = login("quality-admin", "Quality-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 20, position(now.plusSeconds(20), 116.40));
            assertThat(trackTypeAt(bound, now.plusSeconds(20))).isEqualTo("HISTORY");
            assertThat(lastPositionCount(bound)).isZero();

            clock.advance(java.time.Duration.ofSeconds(25));
            assertThat(lastPositionCount(bound)).isZero();

            LocalDateTime current = LocalDateTime.now(clock).withNano(0);
            sendAndExpectSuccess(terminal, 21, position(current, 116.41));
            assertThat(lastPositionVersion(bound)).isEqualTo(1);
            assertThat(lastPositionLng(bound)).isEqualByComparingTo("116.41");

            sendAndExpectSuccess(terminal, 22, position(current.plusSeconds(40), 116.42));
            assertThat(trackTypeAt(bound, current.plusSeconds(40))).isNull();
            assertThat(anomalyReasons(bound)).contains("FUTURE_BEYOND_TOLERANCE");
        }
    }

    @Test
    void invalidFixIsStoredWithNullCoordinatesAndAbsentFromMonitor() throws Exception {
        String token = login("quality-admin", "Quality-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 30,
                    new PositionReport(0, 1, 39.908, 116.31, 10, 600, 90, now, List.of()));
            var row = jdbc.queryForMap(
                    "SELECT lng, lat, locate_state FROM track_point WHERE terminal_id = ? AND gps_time = ?",
                    bound.terminalId(), now);
            assertThat(row.get("lng")).isNull();
            assertThat(row.get("lat")).isNull();
            assertThat(row.get("locate_state")).isEqualTo("INVALID");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE terminal_id = ?"
                    + " AND raw_body IS NOT NULL AND payload_hash IS NOT NULL", Long.class,
                    bound.terminalId())).isEqualTo(1);
            assertThat(lastPositionCount(bound)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE aggregate_id = ?",
                    Long.class, bound.vehicleId())).isZero();

            JsonNode vehicles = json.readTree(mvc.perform(get("/api/v1/monitor/snapshot").header("satoken", token))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("vehicles");
            JsonNode vehicle = null;
            for (JsonNode candidate : vehicles) {
                if (candidate.path("vehicleId").asLong() == bound.vehicleId()) vehicle = candidate;
            }
            assertThat(vehicle).isNotNull();
            assertThat(vehicle.path("position").isNull()).isTrue();

            sendAndExpectSuccess(terminal, 31, position(now.plusSeconds(2), 116.34));
            assertThat(trackTypeAt(bound, now.plusSeconds(2))).isEqualTo("HISTORY");
            assertThat(lastPositionVersion(bound)).isZero();

            clock.advance(java.time.Duration.ofSeconds(5));
            sendAndExpectSuccess(terminal, 32, position(LocalDateTime.now(clock).withNano(0), 116.35));
            assertThat(lastPositionVersion(bound)).isEqualTo(1);
            assertThat(lastPositionLng(bound)).isEqualByComparingTo("116.35");
        }
    }

    @Test
    void concurrentSameSecondDifferentContentKeepsFirstAndRecordsSingleConflict() throws Exception {
        String token = login("quality-admin", "Quality-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            // 两帧先后写入、未等首帧应答即发第二帧，模拟 ACK 前到达的并发同秒冲突。
            terminal.send(0x0200, 50, position(now, 116.50).encode());
            terminal.send(0x0200, 51, position(now, 116.51).encode());
            for (int serial : new int[] {50, 51}) {
                TerminalGeneralResponse response = TerminalGeneralResponse.parse(terminal.receive().body());
                assertThat(response.responseId()).isEqualTo(0x0200);
                assertThat(response.responseSerial()).isEqualTo(serial);
                assertThat(response.result()).isZero();
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE terminal_id = ?", Long.class,
                    bound.terminalId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT lng FROM track_point WHERE terminal_id = ? AND gps_time = ?",
                    BigDecimal.class, bound.terminalId(), now)).isEqualByComparingTo("116.50");
            assertThat(lastPositionVersion(bound)).isEqualTo(1);
            assertThat(lastPositionLng(bound)).isEqualByComparingTo("116.50");
            assertThat(jdbc.queryForObject("SELECT version FROM notification_outbox WHERE aggregate_id = ?",
                    Long.class, bound.vehicleId())).isEqualTo(1);
            var conflict = jdbc.queryForMap(
                    "SELECT reason, gps_time, body_length FROM telemetry_anomaly WHERE terminal_id = ?",
                    bound.terminalId());
            assertThat(conflict.get("reason")).isEqualTo("CONFLICTING_CONTENT");
            assertThat(jdbc.queryForObject("SELECT gps_time FROM telemetry_anomaly WHERE terminal_id = ?",
                    LocalDateTime.class, bound.terminalId())).isEqualTo(now);
            assertThat(((Number) conflict.get("body_length")).intValue()).isEqualTo(28);
        }
    }

    @Test
    void invalidTimeAndOutOfRangeCoordinatesAreRecordedAsAnomaliesWithSuccessAck() throws Exception {
        String token = login("quality-admin", "Quality-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        try (TerminalClient terminal = authenticated(bound)) {
            terminal.send(0x0200, 60, rawPosition(3, 39_908_000, 116_310_000, 600, 90, "261332120000"));
            TerminalGeneralResponse badTime = TerminalGeneralResponse.parse(terminal.receive().body());
            assertThat(badTime.responseSerial()).isEqualTo(60);
            assertThat(badTime.result()).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT reason FROM telemetry_anomaly WHERE terminal_id = ? AND gps_time IS NULL",
                    String.class, bound.terminalId())).isEqualTo("INVALID_TIME");

            terminal.send(0x0200, 61, rawPosition(3, 91_000_000, 116_310_000, 600, 90, "260101120000"));
            TerminalGeneralResponse badCoordinate = TerminalGeneralResponse.parse(terminal.receive().body());
            assertThat(badCoordinate.responseSerial()).isEqualTo(61);
            assertThat(badCoordinate.result()).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT reason FROM telemetry_anomaly WHERE terminal_id = ? AND gps_time IS NOT NULL",
                    String.class, bound.terminalId())).isEqualTo("COORDINATE_OUT_OF_RANGE");

            assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE terminal_id = ?", Long.class,
                    bound.terminalId())).isZero();
            assertThat(lastPositionCount(bound)).isZero();
        }
    }

    @Test
    void pointForDisabledVehicleIsRecordedAsUnassignableAnomalyWithoutGuessing() throws Exception {
        String token = login("quality-admin", "Quality-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        try (TerminalClient terminal = authenticated(bound)) {
            // 停用车辆但保留当前代次会话：位置无法归属，只能记录异常，不能猜测车辆或静默丢弃。
            jdbc.update("UPDATE vehicle SET status = 'DISABLED' WHERE id = ?", bound.vehicleId());
            sendAndExpectSuccess(terminal, 70, position(now, 116.60));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE vehicle_id = ?", Long.class,
                    bound.vehicleId())).isZero();
            assertThat(jdbc.queryForObject("SELECT reason FROM telemetry_anomaly WHERE terminal_id = ?",
                    String.class, bound.terminalId())).isEqualTo("UNASSIGNABLE");
        }
    }

    @Test
    void pointOutsideRetentionIsRecordedAsRestrictedAnomalyWithoutRebuildingPartitions() throws Exception {
        String token = login("quality-admin", "Quality-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime expired = LocalDateTime.now(clock).withNano(0).minusDays(30);
        int partitionsBefore = trackPointPartitionCount();
        try (TerminalClient terminal = authenticated(bound)) {
            sendAndExpectSuccess(terminal, 80, position(expired, 116.70));
            assertThat(trackPointPartitionCount()).isEqualTo(partitionsBefore);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE terminal_id = ?", Long.class,
                    bound.terminalId())).isZero();
            var anomaly = jdbc.queryForMap(
                    "SELECT reason, payload_hash, body_length, raw_body FROM telemetry_anomaly WHERE terminal_id = ?",
                    bound.terminalId());
            assertThat(anomaly.get("reason")).isEqualTo("RETENTION_OUTSIDE");
            assertThat(anomaly.get("payload_hash")).isNotNull();
            assertThat(((Number) anomaly.get("body_length")).intValue()).isEqualTo(28);
            assertThat((byte[]) anomaly.get("raw_body")).hasSize(28);
        }
    }

    private int trackPointPartitionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.partitions"
                + " WHERE table_schema = DATABASE() AND table_name = 'track_point'", Integer.class);
    }

    private byte[] rawPosition(long status, long latitudeRaw, long longitudeRaw, int speed, int direction,
                               String bcdTime) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeInt(out, 0);
        writeInt(out, status);
        writeInt(out, latitudeRaw);
        writeInt(out, longitudeRaw);
        writeShort(out, 10);
        writeShort(out, speed);
        writeShort(out, direction);
        out.writeBytes(Bcd.encode(bcdTime));
        return out.toByteArray();
    }

    private static void writeInt(java.io.ByteArrayOutputStream out, long value) {
        out.write((int) (value >>> 24));
        out.write((int) (value >>> 16));
        out.write((int) (value >>> 8));
        out.write((int) value);
    }

    private static void writeShort(java.io.ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }
    private void sendAndExpectSuccess(TerminalClient terminal, int serial, PositionReport report) throws Exception {
        terminal.send(0x0200, serial, report.encode());
        TerminalGeneralResponse response = TerminalGeneralResponse.parse(terminal.receive().body());
        assertThat(response.responseId()).isEqualTo(0x0200);
        assertThat(response.responseSerial()).isEqualTo(serial);
        assertThat(response.result()).isZero();
    }

    private long lastPositionVersion(BoundTerminal bound) {
        List<Long> versions = jdbc.queryForList(
                "SELECT version FROM vehicle_last_position WHERE vehicle_id = ?", Long.class, bound.vehicleId());
        return versions.isEmpty() ? 0 : versions.getFirst();
    }

    private long lastPositionCount(BoundTerminal bound) {
        return jdbc.queryForObject("SELECT count(*) FROM vehicle_last_position WHERE vehicle_id = ?",
                Long.class, bound.vehicleId());
    }

    private BigDecimal lastPositionLng(BoundTerminal bound) {
        return jdbc.queryForObject("SELECT lng FROM vehicle_last_position WHERE vehicle_id = ?",
                BigDecimal.class, bound.vehicleId());
    }

    private String trackTypeAt(BoundTerminal bound, LocalDateTime time) {
        List<String> types = jdbc.queryForList(
                "SELECT report_type FROM track_point WHERE terminal_id = ? AND gps_time = ?",
                String.class, bound.terminalId(), time);
        return types.isEmpty() ? null : types.getFirst();
    }

    private List<String> anomalyReasons(BoundTerminal bound) {
        return jdbc.queryForList("SELECT reason FROM telemetry_anomaly WHERE terminal_id = ?",
                String.class, bound.terminalId());
    }

    private PositionReport position(LocalDateTime time, double longitude) {
        return new PositionReport(0, 3, 39.908, longitude, 10, 600, 90, time, List.of());
    }

    private String login(String username, String password) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .path("token").asText();
    }

    private BoundTerminal boundTerminal(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String terminalNo = "013800139000" + String.format("%08d", SEQUENCE.getAndIncrement());
        long fleet = create(token, "/fleets", Map.of("fleetCode", "Q" + suffix, "fleetName", "定位质量" + suffix));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", "质量" + suffix, "fleetId", fleet));
        long terminal = create(token, "/terminals", Map.of("terminalNo", terminalNo));
        mvc.perform(put("/api/v1/vehicles/" + vehicle + "/terminal").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("terminalId", terminal))))
                .andExpect(status().isOk());
        return new BoundTerminal(terminal, vehicle, fleet, terminalNo);
    }

    private long create(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1" + path).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("id").asLong();
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
        MutableClock qualityClock() {
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
