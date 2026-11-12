package com.vms.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vms.access.TerminalAccessPort;
import com.vms.protocol.*;
import com.vms.support.IntegrationContainers;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "vms.bootstrap-admin.username=telemetry-admin",
        "vms.bootstrap-admin.password=Telemetry-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0"
})
@Import(IntegrationContainers.class)
@AutoConfigureMockMvc
class TelemetryFlowTest {
    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TerminalAccessPort access;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired org.testcontainers.containers.GenericContainer<?> redisContainer;
    @Autowired org.testcontainers.containers.MySQLContainer<?> mysqlContainer;
    @LocalServerPort int httpPort;

    @Test
    void successfulPositionAckMeansCommittedPositionIsVisibleInAuthorizedSnapshot() throws Exception {
        String token = login("telemetry-admin", "Telemetry-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        try (TerminalClient terminal = authenticated(bound)) {
            terminal.send(0x0200, 3, position(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0), 116.397).encode());
            TerminalGeneralResponse ack = TerminalGeneralResponse.parse(terminal.receive().body());
            assertThat(ack.responseId()).isEqualTo(0x0200);
            assertThat(ack.responseSerial()).isEqualTo(3);
            assertThat(ack.result()).isZero();
            JsonNode vehicle = snapshotVehicle(token, bound.vehicleId());
            assertThat(vehicle.path("communicationStatus").asText()).isEqualTo("ONLINE");
            JsonNode position = vehicle.path("position");
            assertThat(position.path("lng").asDouble()).isEqualTo(116.397);
            assertThat(position.path("lat").asDouble()).isEqualTo(39.908);
            assertThat(position.path("speedKph").asDouble()).isEqualTo(60.0);
            assertThat(position.path("directionDeg").asInt()).isEqualTo(90);
            assertThat(position.path("accOn").asBoolean()).isTrue();
            assertThat(position.path("fixValid").asBoolean()).isTrue();
            assertThat(position.path("version").asLong()).isEqualTo(1);
            assertThat(position.path("gpsTime").asText()).endsWith("+08:00");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE vehicle_id = ?", Long.class,
                    bound.vehicleId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT version FROM vehicle_monitor_state WHERE vehicle_id = ?", Long.class,
                    bound.vehicleId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT version FROM notification_outbox WHERE aggregate_id = ?", Long.class,
                    bound.vehicleId())).isEqualTo(1);
        }
    }

    @Test
    void websocketReceivesAuthorizedCommittedPositionWithSameVersionAsRest() throws Exception {
        String token = login("telemetry-admin", "Telemetry-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        JsonNode issued = json.readTree(mvc.perform(post("/api/v1/auth/ws-ticket").header("satoken", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        java.util.concurrent.BlockingQueue<String> frames = new java.util.concurrent.LinkedBlockingQueue<>();
        java.net.http.WebSocket ws = java.net.http.HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3))
                .buildAsync(java.net.URI.create("ws://127.0.0.1:" + httpPort + "/ws/monitor?ticket="
                        + issued.path("ticket").asText()), new java.net.http.WebSocket.Listener() {
                    private final StringBuilder text = new StringBuilder();
                    @Override public void onOpen(java.net.http.WebSocket socket) { socket.request(1); }
                    @Override public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket socket,
                            CharSequence data, boolean last) {
                        text.append(data);
                        if (last) { frames.add(text.toString()); text.setLength(0); }
                        socket.request(1);
                        return null;
                    }
                }).get(5, java.util.concurrent.TimeUnit.SECONDS);
        try (TerminalClient terminal = authenticated(bound)) {
            terminal.send(0x0200, 3, position(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0), 116.398).encode());
            assertThat(TerminalGeneralResponse.parse(terminal.receive().body()).result()).isZero();
            String received = frames.poll(4, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(received).isNotNull();
            JsonNode frame = json.readTree(received);
            assertThat(frame.path("type").asText()).isEqualTo("vehicle_position");
            assertThat(frame.path("aggregate_id").asLong()).isEqualTo(bound.vehicleId());
            assertThat(frame.path("version").asLong()).isEqualTo(1);
            assertThat(frame.path("payload").path("position").path("lng").asDouble()).isEqualTo(116.398);
            assertThat(snapshotVehicle(token, bound.vehicleId()).path("position").path("version").asLong())
                    .isEqualTo(frame.path("version").asLong());
        } finally { ws.abort(); }
    }

    @Test
    void databaseLockPreventsSuccessAckUntilCommitCanComplete() throws Exception {
        String token = login("telemetry-admin", "Telemetry-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        try (TerminalClient terminal = authenticated(bound);
             java.sql.Connection blocker = jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT id FROM terminal WHERE id=? FOR UPDATE")) {
                lock.setLong(1, bound.terminalId());
                lock.executeQuery().close();
            }
            terminal.send(0x0200, 3, position(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0), 116.4).encode());
            terminal.socket.setSoTimeout(350);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> terminal.socket.getInputStream().read())
                    .isInstanceOf(java.net.SocketTimeoutException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE vehicle_id=?", Long.class,
                    bound.vehicleId())).isZero();
            blocker.rollback();
            terminal.socket.setSoTimeout(5000);
            assertThat(TerminalGeneralResponse.parse(terminal.receive().body()).result()).isZero();
            assertThat(snapshotVehicle(token, bound.vehicleId()).path("position").path("version").asLong()).isEqualTo(1);
        }
    }

    @Test
    void failedOutboxWriteRollsBackPositionSnapshotAndStateWithoutSuccessAck() throws Exception {
        String token = login("telemetry-admin", "Telemetry-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        jdbc.execute("ALTER TABLE notification_outbox ADD CONSTRAINT injected_position_failure CHECK (aggregate_id <> "
                + bound.vehicleId() + ")");
        try (TerminalClient terminal = authenticated(bound)) {
            terminal.send(0x0200, 3, position(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0), 116.401).encode());
            assertThat(terminal.socket.getInputStream().read()).isEqualTo(-1);
            for (String table : List.of("track_point", "vehicle_last_position", "vehicle_monitor_state")) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE vehicle_id=?", Long.class,
                        bound.vehicleId())).as(table + " rolled back").isZero();
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE aggregate_id=?", Long.class,
                    bound.vehicleId())).isZero();
        } finally { jdbc.execute("ALTER TABLE notification_outbox DROP CHECK injected_position_failure"); }
    }

    @Test
    void firstRestSnapshotAndEveryWebsocketOutputUseCurrentFleetAuthorization() throws Exception {
        String admin = login("telemetry-admin", "Telemetry-Pass-2026");
        BoundTerminal allowed = boundTerminal(admin);
        BoundTerminal denied = boundTerminal(admin);
        String name = "leader-" + UUID.randomUUID().toString().substring(0, 8);
        create(admin, "/users", Map.of("username", name, "password", "Leader-Pass-2026",
                "roleCode", "FLEET_LEADER", "fleetIds", List.of(allowed.fleetId())));
        String leader = login(name, "Leader-Pass-2026");
        JsonNode snapshot = json.readTree(mvc.perform(get("/api/v1/monitor/snapshot").header("satoken", leader))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(snapshot.path("vehicles").size()).isEqualTo(1);
        assertThat(snapshot.path("vehicles").get(0).path("vehicleId").asLong()).isEqualTo(allowed.vehicleId());
        mvc.perform(get("/api/v1/monitor/snapshot").param("fleetId", Long.toString(denied.fleetId()))
                .header("satoken", leader)).andExpect(status().isForbidden());
        String emptyName = "empty-" + UUID.randomUUID().toString().substring(0, 8);
        create(admin, "/users", Map.of("username", emptyName, "password", "Leader-Pass-2026",
                "roleCode", "FLEET_LEADER", "fleetIds", List.of()));
        String empty = login(emptyName, "Leader-Pass-2026");
        assertThat(json.readTree(mvc.perform(get("/api/v1/monitor/snapshot").header("satoken", empty))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .path("vehicles").size()).isZero();
        try (WsCapture leaderWs = subscribe(leader); WsCapture emptyWs = subscribe(empty);
             TerminalClient a = authenticated(allowed); TerminalClient b = authenticated(denied)) {
            LocalDateTime time = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0);
            a.send(0x0200, 3, position(time, 116.41).encode());
            assertThat(TerminalGeneralResponse.parse(a.receive().body()).result()).isZero();
            b.send(0x0200, 3, position(time, 116.42).encode());
            assertThat(TerminalGeneralResponse.parse(b.receive().body()).result()).isZero();
            JsonNode frame = json.readTree(leaderWs.messages.poll(4, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(frame.path("aggregate_id").asLong()).isEqualTo(allowed.vehicleId());
            assertThat(leaderWs.messages.poll(2500, java.util.concurrent.TimeUnit.MILLISECONDS)).isNull();
            assertThat(emptyWs.messages.poll()).isNull();
        }
    }

    @Test
    void wsTicketsAreRequiredSingleUseAndBoundToTheIssuingSession() throws Exception {
        String token = login("telemetry-admin", "Telemetry-Pass-2026");
        assertHandshakeRejected("missing");
        String ticket = issueTicket(token);
        try (WsCapture connection = connectWs(ticket)) {
            assertHandshakeRejected(ticket);
        }
        String staleTicket = issueTicket(token);
        login("telemetry-admin", "Telemetry-Pass-2026");
        assertHandshakeRejected(staleTicket);
    }

    @Test
    void positionUpdatesAreCoalescedToLatestVehicleVersion() throws Exception {
        String token = login("telemetry-admin", "Telemetry-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        try (WsCapture ws = subscribe(token); TerminalClient terminal = authenticated(bound)) {
            LocalDateTime time = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(10).withNano(0);
            terminal.send(0x0200, 3, position(time, 116.43).encode());
            terminal.receive();
            assertThat(ws.messages.poll(4, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
            terminal.send(0x0200, 4, position(time.plusSeconds(1), 116.44).encode());
            terminal.receive();
            terminal.send(0x0200, 5, position(time.plusSeconds(2), 116.45).encode());
            terminal.receive();
            String text = ws.messages.poll(4, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(text).isNotNull();
            JsonNode frame = json.readTree(text);
            assertThat(frame.path("version").asLong()).isEqualTo(3);
            assertThat(frame.path("payload").path("position").path("lng").asDouble()).isEqualTo(116.45);
        }
    }

    @Test
    void redisOutageDoesNotDelayAckAndRecoveryRebuildsCommittedCache() throws Exception {
        String token = login("telemetry-admin", "Telemetry-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        try (TerminalClient terminal = authenticated(bound)) {
            var docker = redisContainer.getDockerClient();
            docker.pauseContainerCmd(redisContainer.getContainerId()).exec();
            try {
                long started = System.nanoTime();
                terminal.send(0x0200, 3, position(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0), 116.46).encode());
                assertThat(TerminalGeneralResponse.parse(terminal.receive().body()).result()).isZero();
                assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(2));
                assertThat(jdbc.queryForObject("SELECT version FROM vehicle_last_position WHERE vehicle_id=?", Long.class,
                        bound.vehicleId())).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE aggregate_id=?", String.class,
                        bound.vehicleId())).isIn("PENDING", "RETRY", "DONE");
            } finally { docker.unpauseContainerCmd(redisContainer.getContainerId()).exec(); }
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(redis.opsForValue().get("vms:position:" + bound.vehicleId())).contains("116.460000");
                assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE aggregate_id=?", String.class,
                        bound.vehicleId())).isEqualTo("DONE");
            });
            assertThat(snapshotVehicle(token, bound.vehicleId()).path("position").path("version").asLong()).isEqualTo(1);
        }
    }

    @Test
    void killedDatabaseSessionCannotProduceSuccessAckOrPartialPosition() throws Exception {
        String token = login("telemetry-admin", "Telemetry-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        try (TerminalClient terminal = authenticated(bound);
             java.sql.Connection blocker = jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT id FROM terminal WHERE id=? FOR UPDATE")) {
                lock.setLong(1, bound.terminalId());
                lock.executeQuery().close();
            }
            terminal.send(0x0200, 3, position(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0), 116.47).encode());
            try (var root = java.sql.DriverManager.getConnection(mysqlContainer.getJdbcUrl(), "root", mysqlContainer.getPassword())) {
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
                    try (var statement = root.createStatement(); var rows = statement.executeQuery(
                            "SELECT ID FROM information_schema.PROCESSLIST WHERE DB='vms' AND INFO LIKE 'SELECT status FROM terminal%FOR UPDATE'")) {
                        assertThat(rows.next()).isTrue();
                        long id = rows.getLong(1);
                        try (var kill = root.createStatement()) { kill.execute("KILL CONNECTION " + id); }
                    }
                });
            }
            assertThat(terminal.socket.getInputStream().read()).isEqualTo(-1);
            blocker.rollback();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE vehicle_id=?", Long.class,
                    bound.vehicleId())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_last_position WHERE vehicle_id=?", Long.class,
                    bound.vehicleId())).isZero();
        }
    }

    @Test
    void cacheCanBeRebuiltAfterRedisLosesAnAlreadyDispatchedPosition() throws Exception {
        String token = login("telemetry-admin", "Telemetry-Pass-2026");
        BoundTerminal bound = boundTerminal(token);
        try (TerminalClient terminal = authenticated(bound)) {
            terminal.send(0x0200, 3, position(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0), 116.48).encode());
            assertThat(TerminalGeneralResponse.parse(terminal.receive().body()).result()).isZero();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE aggregate_id=?", String.class,
                        bound.vehicleId())).isEqualTo("DONE");
                assertThat(redis.opsForValue().get("vms:position:" + bound.vehicleId())).isNotNull();
            });
            redis.delete("vms:position:" + bound.vehicleId());
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).untilAsserted(() ->
                    assertThat(redis.opsForValue().get("vms:position:" + bound.vehicleId())).contains("116.480000"));
        }
    }

    private String issueTicket(String token) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/ws-ticket").header("satoken", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("ticket").asText();
    }

    private WsCapture subscribe(String token) throws Exception { return connectWs(issueTicket(token)); }
    private WsCapture connectWs(String ticket) throws Exception {
        WsCapture capture = new WsCapture();
        capture.socket = java.net.http.HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3))
                .buildAsync(java.net.URI.create("ws://127.0.0.1:" + httpPort + "/ws/monitor?ticket=" + ticket), capture)
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        return capture;
    }

    private void assertHandshakeRejected(String ticket) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> connectWs(ticket))
                .hasCauseInstanceOf(java.net.http.WebSocketHandshakeException.class);
    }

    private static final class WsCapture implements java.net.http.WebSocket.Listener, AutoCloseable {
        final java.util.concurrent.BlockingQueue<String> messages = new java.util.concurrent.LinkedBlockingQueue<>();
        final StringBuilder text = new StringBuilder();
        java.net.http.WebSocket socket;
        @Override public void onOpen(java.net.http.WebSocket socket) { socket.request(1); }
        @Override public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket socket,
                CharSequence data, boolean last) {
            text.append(data);
            if (last) { messages.add(text.toString()); text.setLength(0); }
            socket.request(1);
            return null;
        }
        @Override public void close() { if (socket != null) socket.abort(); }
    }

    private PositionReport position(LocalDateTime time, double longitude) {
        return new PositionReport(0, 3, 39.908, longitude, 10, 600, 90, time, List.of());
    }

    private JsonNode snapshotVehicle(String token, long vehicleId) throws Exception {
        JsonNode snapshot = json.readTree(mvc.perform(get("/api/v1/monitor/snapshot").header("satoken", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        for (JsonNode vehicle : snapshot.path("vehicles")) {
            if (vehicle.path("vehicleId").asLong() == vehicleId) return vehicle;
        }
        throw new AssertionError("Snapshot is missing vehicle " + vehicleId);
    }

    private BoundTerminal boundTerminal(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String terminalNo = "013800139000" + String.format("%08d", SEQUENCE.getAndIncrement());
        long fleet = create(token, "/fleets", Map.of("fleetCode", "P" + suffix, "fleetName", "模拟定位" + suffix));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", "模拟" + suffix, "fleetId", fleet));
        long terminal = create(token, "/terminals", Map.of("terminalNo", terminalNo));
        mvc.perform(put("/api/v1/vehicles/" + vehicle + "/terminal").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("terminalId", terminal))))
                .andExpect(status().isOk());
        return new BoundTerminal(terminal, vehicle, fleet, terminalNo);
    }

    private TerminalClient authenticated(BoundTerminal bound) throws Exception {
        TerminalClient client = new TerminalClient(access.port(), bound.terminalNo());
        client.send(0x0100, 1, new TerminalRegister(1, 1, "ABC12", "TESTMODEL", bound.terminalNo(), 2, "模拟车辆").encode());
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
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("token").asText();
    }

    private long create(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1" + path).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray()).path("id").asLong();
    }

    private record BoundTerminal(long terminalId, long vehicleId, long fleetId, String terminalNo) { }

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
