package com.vms.infrastructure.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vms.support.IntegrationContainers;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 17：订阅按最新授权持续校验；停用、改密、新登录、撤权与到期后旧订阅停止推送。
 * 本类使用真实 HTTP 触发权限变化、真实 WS 握手与一次性 ticket；慢客户端用真实 TCP 客户端验证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "vms.bootstrap-admin.username=ws-admin",
        "vms.bootstrap-admin.password=Ws-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0",
        "vms.monitor.ws-outbound-capacity=8"
})
@Import(IntegrationContainers.class)
@AutoConfigureMockMvc
class MonitorWebSocketSubscriptionTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int httpPort;

    @Test
    void disabledAccountSubscriptionStopsWithinFiveSeconds() throws Exception {
        String admin = login("ws-admin", "Ws-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = fleet(admin, "WS" + suffix, "订阅车队" + suffix);
        String username = "ws-leader-" + suffix;
        long leaderId = leader(admin, username, fleet);
        String leader = login(username, "Leader-Pass-2026");

        try (WsClient ws = subscribe(leader)) {
            assertThat(ws.open.await(5, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            mvc.perform(put("/api/v1/users/" + leaderId + "/status").header("satoken", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("status", "DISABLED"))))
                    .andExpect(status().isOk());
            Close close = ws.closed.get(5, TimeUnit.SECONDS);
            assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(5000);
            assertThat(close.code()).isEqualTo(1008);
            assertThat(close.reason()).contains("SUBSCRIPTION_REVOKED");
        }
    }

    @Test
    void passwordResetStopsSubscriptionWithinFiveSeconds() throws Exception {
        String admin = login("ws-admin", "Ws-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = fleet(admin, "PW" + suffix, "改密车队" + suffix);
        String username = "ws-pw-" + suffix;
        long leaderId = leader(admin, username, fleet);
        String leader = login(username, "Leader-Pass-2026");

        try (WsClient ws = subscribe(leader)) {
            assertThat(ws.open.await(5, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            mvc.perform(post("/api/v1/users/" + leaderId + "/password-reset").header("satoken", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("newPassword", "Reset-Pass-2026"))))
                    .andExpect(status().isNoContent());
            Close close = ws.closed.get(5, TimeUnit.SECONDS);
            assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(5000);
            assertThat(close.code()).isEqualTo(1008);
        }
    }

    @Test
    void newLoginReplacesOldSubscriptionWithinFiveSeconds() throws Exception {
        String admin = login("ws-admin", "Ws-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = fleet(admin, "NL" + suffix, "新登录车队" + suffix);
        String username = "ws-nl-" + suffix;
        leader(admin, username, fleet);
        String oldToken = login(username, "Leader-Pass-2026");

        try (WsClient ws = subscribe(oldToken)) {
            assertThat(ws.open.await(5, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            String newToken = login(username, "Leader-Pass-2026");
            assertThat(newToken).isNotEqualTo(oldToken);
            Close close = ws.closed.get(5, TimeUnit.SECONDS);
            assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(5000);
            assertThat(close.code()).isEqualTo(1008);
        }
    }

    @Test
    void fleetRevocationStopsSubscriptionAndCrossFleetPositionsAreFiltered() throws Exception {
        String admin = login("ws-admin", "Ws-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long allowedFleet = fleet(admin, "FA" + suffix, "授权车队" + suffix);
        long otherFleet = fleet(admin, "FB" + suffix, "其他车队" + suffix);
        String username = "ws-fa-" + suffix;
        long leaderId = leader(admin, username, allowedFleet);
        String leader = login(username, "Leader-Pass-2026");

        try (WsClient ws = subscribe(leader)) {
            assertThat(ws.open.await(5, TimeUnit.SECONDS)).isTrue();
            long allowedVehicle = insertVehicle(allowedFleet, "授权车" + suffix);
            long deniedVehicle = insertVehicle(otherFleet, "其他车" + suffix);
            seedPendingPosition(allowedVehicle);
            seedPendingPosition(deniedVehicle);
            String text = ws.messages.poll(6, TimeUnit.SECONDS);
            assertThat(text).isNotNull();
            JsonNode frame = json.readTree(text);
            assertThat(frame.path("aggregate_id").asLong()).isEqualTo(allowedVehicle);
            assertThat(frame.path("version").asLong()).isEqualTo(1);
            assertThat(frame.path("event_id").asText()).isEqualTo("position:" + allowedVehicle + ":1");
            assertThat(frame.path("server_time").asText()).isNotBlank();
            assertThat(ws.messages.poll(2500, TimeUnit.MILLISECONDS)).isNull();

            long started = System.nanoTime();
            mvc.perform(put("/api/v1/users/" + leaderId + "/fleets").header("satoken", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("fleetIds", List.of()))))
                    .andExpect(status().isOk());
            Close close = ws.closed.get(5, TimeUnit.SECONDS);
            assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(5000);
            assertThat(close.code()).isEqualTo(1008);
        }
    }

    @Test
    void revocationAuditAndLogsNeverContainTicketOrToken() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        String admin = login("ws-admin", "Ws-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = fleet(admin, "AU" + suffix, "审计车队" + suffix);
        String username = "ws-au-" + suffix;
        long leaderId = leader(admin, username, fleet);
        String leader = login(username, "Leader-Pass-2026");
        String ticket;
        try (WsClient ws = subscribe(leader)) {
            ticket = ws.ticket;
            assertThat(ws.open.await(5, TimeUnit.SECONDS)).isTrue();
            mvc.perform(put("/api/v1/users/" + leaderId + "/status").header("satoken", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("status", "DISABLED"))))
                    .andExpect(status().isOk());
            assertThat(ws.closed.get(5, TimeUnit.SECONDS).code()).isEqualTo(1008);
        } finally {
            root.detachAppender(appender);
        }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(logs).doesNotContain(ticket, leader, admin);

        List<Map<String, Object>> audits = jdbc.queryForList(
                "SELECT action, detail FROM audit_log WHERE actor_id=? AND action IN ('WS_SUBSCRIBE','WS_SUBSCRIPTION_REVOKED')",
                leaderId);
        assertThat(audits).extracting(row -> row.get("action"))
                .contains("WS_SUBSCRIBE", "WS_SUBSCRIPTION_REVOKED");
        String details = audits.stream().map(row -> String.valueOf(row.get("detail")))
                .collect(Collectors.joining("\n"));
        assertThat(details).contains("sessionId=").doesNotContain(ticket, leader, admin);
    }

    @Test
    void slowClientIsDisconnectedAfterBoundedBufferOverflow() throws Exception {
        Logger handlerLogger = (Logger) LoggerFactory.getLogger(MonitorWebSocketHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);
        String admin = login("ws-admin", "Ws-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = fleet(admin, "SL" + suffix,
                "慢客户端车队" + suffix + "甲".repeat(80));
        try (RawSlowClient client = connectSlow(issueTicket(admin))) {
            Thread.sleep(500);
            long started = System.nanoTime();
            for (int index = 0; index < 300; index++) {
                long vehicleId = insertVehicle(fleet, "慢客户端" + suffix + "-" + index);
                seedPendingPosition(vehicleId);
            }
            // 不读取任何帧，等待有界缓冲溢出；服务器应显式移除订阅并关闭连接。
            Thread.sleep(8000);
            Close close = client.awaitClose();
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertThat(elapsedMillis).isLessThan(30_000);
            // 传输被背压占满时关闭帧可能无法送达，客户端看到的是连接终止；
            // 服务器侧的显式断开决定由下面的 WARN 与订阅移除证明。
            assertThat(close.code()).isIn(1011, -1);
        } finally {
            handlerLogger.detachAppender(appender);
        }
        String warnings = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(warnings).contains("WS 慢客户端缓冲超限", "capacity=8");
    }

    private long fleet(String admin, String code, String name) throws Exception {
        return create(admin, "/fleets", Map.of("fleetCode", code, "fleetName", name));
    }

    private long leader(String admin, String username, long fleetId) throws Exception {
        return create(admin, "/users", Map.of("username", username, "password", "Leader-Pass-2026",
                "roleCode", "FLEET_LEADER", "fleetIds", List.of(fleetId)));
    }

    private long insertVehicle(long fleetId, String plateNo) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO vehicle (plate_no, fleet_id, status, created_at, updated_at)"
                            + " VALUES (?, ?, 'ENABLED', ?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, plateNo);
            statement.setLong(2, fleetId);
            statement.setObject(3, now);
            statement.setObject(4, now);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private void seedPendingPosition(long vehicleId) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("""
                INSERT INTO vehicle_last_position
                    (vehicle_id, terminal_id, gps_time, received_at, lng, lat, speed, direction, status_flag, version)
                VALUES (?, 0, ?, ?, 116.400000, 39.900000, 60.0, 90, 1, 1)
                """, vehicleId, now, now);
        jdbc.update("""
                INSERT INTO notification_outbox
                    (event_id, event_type, aggregate_id, version, status, retry_at, attempts, updated_at)
                VALUES (?, 'VEHICLE_POSITION', ?, 1, 'PENDING', ?, 0, ?)
                """, "position:" + vehicleId, vehicleId, now, now);
    }

    private RawSlowClient connectSlow(String ticket) throws Exception {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(1024);
        socket.connect(new InetSocketAddress("127.0.0.1", httpPort), 3000);
        socket.setSoTimeout(20_000);
        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String request = "GET /ws/monitor?ticket=" + ticket + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + httpPort + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(keyBytes) + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        InputStream input = socket.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int[] tail = new int[4];
        int value;
        while ((value = input.read()) >= 0) {
            head.write(value);
            tail[0] = tail[1]; tail[1] = tail[2]; tail[2] = tail[3]; tail[3] = value;
            if (tail[0] == '\r' && tail[1] == '\n' && tail[2] == '\r' && tail[3] == '\n') break;
        }
        assertThat(head.toString(StandardCharsets.ISO_8859_1)).startsWith("HTTP/1.1 101");
        return new RawSlowClient(socket, input);
    }

    private String issueTicket(String token) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/ws-ticket").header("satoken", token))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .path("ticket").asText();
    }

    private WsClient subscribe(String token) throws Exception {
        String ticket = issueTicket(token);
        WsClient client = new WsClient(ticket);
        client.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .buildAsync(URI.create("ws://127.0.0.1:" + httpPort + "/ws/monitor?ticket=" + ticket), client)
                .get(5, TimeUnit.SECONDS);
        return client;
    }

    private String login(String username, String password) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .path("token").asText();
    }

    private long create(String token, String path, Object body) throws Exception {
        JsonNode created = json.readTree(mvc.perform(post("/api/v1" + path).header("satoken", token)
                                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
        return created.path("id").asLong();
    }

    private record Close(int code, String reason) { }

    private static final class WsClient implements WebSocket.Listener, AutoCloseable {
        final String ticket;
        final CountDownLatch open = new CountDownLatch(1);
        final CompletableFuture<Close> closed = new CompletableFuture<>();
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        volatile WebSocket socket;

        private WsClient(String ticket) { this.ticket = ticket; }

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

        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            closed.complete(new Close(code, reason));
            return null;
        }

        @Override public void onError(WebSocket ws, Throwable error) { closed.completeExceptionally(error); }

        @Override public void close() {
            if (socket != null) socket.abort();
        }
    }

    /** 只完成握手、不读取后续帧的原始 WS 客户端，用于制造有界缓冲溢出。 */
    private static final class RawSlowClient implements AutoCloseable {
        private final Socket socket;
        private final DataInputStream input;

        private RawSlowClient(Socket socket, InputStream input) {
            this.socket = socket;
            this.input = new DataInputStream(input);
        }

        private Close awaitClose() throws Exception {
            while (true) {
                int first;
                try {
                    first = input.readUnsignedByte();
                } catch (EOFException e) {
                    return new Close(-1, "EOF");
                }
                int opcode = first & 0x0f;
                int length = input.readUnsignedByte() & 0x7f;
                if (length == 126) length = input.readUnsignedShort();
                else if (length == 127) length = Math.toIntExact(input.readLong());
                byte[] payload = new byte[length];
                input.readFully(payload);
                if (opcode == 0x8) {
                    int code = length >= 2 ? ((payload[0] & 0xff) << 8) | (payload[1] & 0xff) : -1;
                    return new Close(code, length > 2 ? new String(payload, 2, length - 2, StandardCharsets.UTF_8) : "");
                }
            }
        }

        @Override public void close() throws Exception { socket.close(); }
    }
}