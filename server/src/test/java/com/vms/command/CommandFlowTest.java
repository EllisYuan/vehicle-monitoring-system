package com.vms.command;

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
import com.vms.protocol.TerminalAuthentication;
import com.vms.protocol.TerminalGeneralResponse;
import com.vms.protocol.TerminalRegister;
import com.vms.protocol.TerminalRegisterResponse;
import com.vms.support.IntegrationContainers;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "vms.bootstrap-admin.username=command-admin",
        "vms.bootstrap-admin.password=Command-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0"
})
@Import({IntegrationContainers.class, CommandFlowTest.TimeConfiguration.class})
@AutoConfigureMockMvc
class CommandFlowTest {

    private static final String ADMIN = "command-admin";
    private static final String ADMIN_PASSWORD = "Command-Pass-2026";
    private static final String IMEI = "012345678901234";
    private static final String VERSION = "00000000000000000001";
    /** 05 独立 fixture 的 0x8300 消息体：标志 1、类型 1、GBK“测试文本”。 */
    private static final String TEXT_BODY_FIXTURE = "01 01 B2 E2 CA D4 CE C4 B1 BE";
    /** 05 独立 fixture 的 0x8202 消息体：间隔 30 秒、有效期 300 秒。 */
    private static final String TRACKING_BODY_FIXTURE = "00 1E 00 00 01 2C";
    private static final AtomicInteger TERMINAL_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TerminalAccessPort access;
    @Autowired com.vms.support.MutableClock clock;

    @Test
    void textCommandReachesTerminalAndAckCompletesWithEpochAndSerial() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        BoundTerminal bound = createBoundTerminal(token);
        try (Socket socket = connect()) {
            authenticate(socket, bound, register(socket, bound, 1), 2);
            long epoch = access.current(bound.terminalNo()).orElseThrow().epoch();

            JsonNode created = submitText(token, bound.terminalId(), "测试文本", null);
            assertThat(created.path("submission").path("status").asText()).isEqualTo("SUBMITTED");
            assertThat(created.path("communication").path("status").asText()).isEqualTo("SENT");
            assertThat(created.path("connectionEpoch").asLong()).isEqualTo(epoch);
            long commandId = created.path("id").asLong();
            int serial = created.path("serialNo").asInt();

            Frame downlink = receive(socket);
            assertThat(downlink.header().messageId()).isEqualTo(0x8300);
            assertThat(downlink.header().serialNumber()).isEqualTo(serial);
            assertThat(hex(downlink.body())).isEqualTo(TEXT_BODY_FIXTURE);

            acknowledge(socket, bound.terminalNo(), 900, serial, 0x8300, 0);
            JsonNode done = awaitStatus(token, commandId, "ACK_SUCCESS");
            assertThat(done.path("communication").path("replyMessageId").asInt()).isEqualTo(0x0001);
            assertThat(done.path("communication").path("replyResult").asInt()).isZero();
            assertThat(done.path("communication").path("replyTime").isNull()).isFalse();
            assertThat(done.path("communication").path("sendTime").isNull()).isFalse();
            assertThat(done.path("serialNo").asInt()).isEqualTo(serial);
            assertThat(done.path("terminalId").asLong()).isEqualTo(bound.terminalId());
            assertThat(done.path("vehicleId").asLong()).isEqualTo(bound.vehicleId());
            assertThat(auditCount("ACK_SUCCESS", "command:" + commandId)).isEqualTo(1);
        }
    }

    @Test
    void trackingCommandUsesIndependentFixtureBody() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        BoundTerminal bound = createBoundTerminal(token);
        try (Socket socket = connect()) {
            authenticate(socket, bound, register(socket, bound, 1), 2);
            JsonNode created = submitTracking(token, bound.terminalId(), 30, 300);
            Frame downlink = receive(socket);
            assertThat(downlink.header().messageId()).isEqualTo(0x8202);
            assertThat(hex(downlink.body())).isEqualTo(TRACKING_BODY_FIXTURE);
            acknowledge(socket, bound.terminalNo(), 901, downlink.header().serialNumber(), 0x8202, 0);
            awaitStatus(token, created.path("id").asLong(), "ACK_SUCCESS");
        }
    }

    @Test
    void textAndTrackingBoundsAreValidatedBeforeSubmission() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        BoundTerminal bound = createBoundTerminal(token);
        try (Socket socket = connect()) {
            authenticate(socket, bound, register(socket, bound, 1), 2);

            expectError(post("/api/v1/commands/text"), token,
                    Map.of("terminalId", bound.terminalId(), "text", "测".repeat(101)),
                    400, "COMMAND_TEXT_TOO_LONG");
            expectError(post("/api/v1/commands/text"), token,
                    Map.of("terminalId", bound.terminalId(), "text", ""),
                    400, "COMMAND_TEXT_INVALID");
            expectError(post("/api/v1/commands/text"), token,
                    Map.of("terminalId", bound.terminalId(), "text", "模拟🚚文本"),
                    400, "COMMAND_TEXT_INVALID");
            for (int interval : new int[] {0, 61}) {
                expectError(post("/api/v1/commands/tracking"), token,
                        Map.of("terminalId", bound.terminalId(), "intervalSeconds", interval, "validSeconds", 300),
                        400, "COMMAND_TRACKING_INVALID");
            }
            expectError(post("/api/v1/commands/tracking"), token,
                    Map.of("terminalId", bound.terminalId(), "intervalSeconds", 30, "validSeconds", 3601),
                    400, "COMMAND_TRACKING_INVALID");

            JsonNode hundred = submitText(token, bound.terminalId(), "测".repeat(100), 1);
            assertThat(hundred.path("textBytes").asInt()).isEqualTo(200);
            Frame textFrame = receive(socket);
            assertThat(textFrame.header().messageId()).isEqualTo(0x8300);
            acknowledge(socket, bound.terminalNo(), 902, textFrame.header().serialNumber(), 0x8300, 0);
            awaitStatus(token, hundred.path("id").asLong(), "ACK_SUCCESS");

            JsonNode boundary = submitTracking(token, bound.terminalId(), 60, 3600);
            Frame trackingFrame = receive(socket);
            assertThat(trackingFrame.header().messageId()).isEqualTo(0x8202);
            acknowledge(socket, bound.terminalNo(), 903, trackingFrame.header().serialNumber(), 0x8202, 0);
            awaitStatus(token, boundary.path("id").asLong(), "ACK_SUCCESS");
        }
    }

    @Test
    void offlineTerminalIsRejectedWithoutQueueing() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        BoundTerminal bound = createBoundTerminal(token);
        expectError(post("/api/v1/commands/text"), token,
                Map.of("terminalId", bound.terminalId(), "text", "离线文本"),
                409, "COMMAND_TERMINAL_OFFLINE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_log WHERE terminal_id = ?",
                Integer.class, bound.terminalId())).isZero();
        assertThat(auditCount("OFFLINE_REJECTED", "terminal:" + bound.terminalId())).isEqualTo(1);
    }

    @Test
    void noAckTimesOutAfterThirtySecondsAndLateAckOnlyLeavesEvidence() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        BoundTerminal bound = createBoundTerminal(token);
        try (Socket socket = connect()) {
            authenticate(socket, bound, register(socket, bound, 1), 2);
            JsonNode created = submitText(token, bound.terminalId(), "超时文本", null);
            long commandId = created.path("id").asLong();
            Frame downlink = receive(socket);

            clock.advance(Duration.ofSeconds(31));
            JsonNode timedOut = awaitStatus(token, commandId, "TIMEOUT");
            assertThat(timedOut.path("communication").path("replyTime").isNull()).isTrue();
            assertThat(auditCount("TIMEOUT", "command:" + commandId)).isEqualTo(1);

            acknowledge(socket, bound.terminalNo(), 904, downlink.header().serialNumber(), 0x8300, 0);
            awaitAudit("LATE_REPLY", "command:" + commandId);
            JsonNode afterLateAck = command(token, commandId);
            assertThat(afterLateAck.path("communication").path("status").asText()).isEqualTo("TIMEOUT");
            assertThat(afterLateAck.path("communication").path("replyTime").isNull()).isTrue();
            assertThat(afterLateAck.path("communication").path("replyResult").isNull()).isTrue();
        }
    }

    @Test
    void staleEpochReplyDoesNotCompleteCommandButNewEpochCan() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        BoundTerminal bound = createBoundTerminal(token);
        try (Socket first = connect(); Socket second = connect()) {
            authenticate(first, bound, register(first, bound, 1), 2);
            JsonNode old = submitText(token, bound.terminalId(), "旧连接文本", null);
            Frame oldDownlink = receive(first);

            authenticate(second, bound, register(second, bound, 3), 4);
            long newEpoch = access.current(bound.terminalNo()).orElseThrow().epoch();
            assertThat(newEpoch).isGreaterThan(old.path("connectionEpoch").asLong());

            acknowledge(second, bound.terminalNo(), 905, oldDownlink.header().serialNumber(), 0x8300, 0);
            awaitAudit("STALE_EPOCH_REPLY", "command:" + old.path("id").asLong());
            JsonNode unchanged = command(token, old.path("id").asLong());
            assertThat(unchanged.path("communication").path("status").asText()).isEqualTo("SENT");
            assertThat(unchanged.path("communication").path("replyTime").isNull()).isTrue();

            JsonNode fresh = submitText(token, bound.terminalId(), "新连接文本", null);
            assertThat(fresh.path("connectionEpoch").asLong()).isEqualTo(newEpoch);
            Frame freshDownlink = receive(second);
            acknowledge(second, bound.terminalNo(), 906, freshDownlink.header().serialNumber(), 0x8300, 0);
            awaitStatus(token, fresh.path("id").asLong(), "ACK_SUCCESS");
        }
    }

    @Test
    void terminalRejectionMarksCommandFailed() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        BoundTerminal bound = createBoundTerminal(token);
        try (Socket socket = connect()) {
            authenticate(socket, bound, register(socket, bound, 1), 2);
            JsonNode created = submitText(token, bound.terminalId(), "拒绝文本", null);
            Frame downlink = receive(socket);
            acknowledge(socket, bound.terminalNo(), 907, downlink.header().serialNumber(), 0x8300, 1);
            JsonNode failed = awaitStatus(token, created.path("id").asLong(), "FAILED");
            assertThat(failed.path("communication").path("failureReason").asText()).isEqualTo("TERMINAL_REJECTED");
            assertThat(failed.path("communication").path("replyResult").asInt()).isEqualTo(1);
        }
    }

    @Test
    void logoutFailsInFlightCommand() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        BoundTerminal bound = createBoundTerminal(token);
        try (Socket socket = connect()) {
            authenticate(socket, bound, register(socket, bound, 1), 2);
            JsonNode created = submitText(token, bound.terminalId(), "注销前指令", null);
            receive(socket);
            send(socket, frame(bound.terminalNo(), 0x0003, 908, new byte[0]));
            Frame logout = receive(socket);
            assertThat(TerminalGeneralResponse.parse(logout.body()).responseId()).isEqualTo(0x0003);

            JsonNode failed = awaitStatus(token, created.path("id").asLong(), "FAILED");
            assertThat(failed.path("communication").path("failureReason").asText()).isEqualTo("LOGOUT");
            assertThat(auditCount("LOGOUT_FAILED", "terminal:" + bound.terminalId())).isEqualTo(1);
        }
    }

    @Test
    void fleetLeaderCannotSendAndDeniedAttemptIsAudited() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = create(token, "/fleets", Map.of("fleetCode", "C" + suffix, "fleetName", "指令只读车队"));
        BoundTerminal bound = createBoundTerminal(token, fleet, suffix);
        create(token, "/users", Map.of("username", "leader-" + suffix, "password", "Leader-Pass-2026",
                "roleCode", "FLEET_LEADER", "fleetIds", java.util.List.of(fleet)));
        String leader = login("leader-" + suffix, "Leader-Pass-2026");

        expectError(post("/api/v1/commands/text"), leader,
                Map.of("terminalId", bound.terminalId(), "text", "车队长文本"),
                403, "FORBIDDEN");
        assertThat(auditCount("DENIED", "terminal:" + bound.terminalId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_log WHERE terminal_id = ?",
                Integer.class, bound.terminalId())).isZero();
    }

    @Test
    void manualRetryCreatesNewRecordAndKeepsHistory() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        BoundTerminal bound = createBoundTerminal(token);
        try (Socket socket = connect()) {
            authenticate(socket, bound, register(socket, bound, 1), 2);
            JsonNode original = submitText(token, bound.terminalId(), "重试文本", null);
            Frame firstDownlink = receive(socket);
            acknowledge(socket, bound.terminalNo(), 909, firstDownlink.header().serialNumber(), 0x8300, 1);
            awaitStatus(token, original.path("id").asLong(), "FAILED");

            var retried = mvc.perform(post("/api/v1/commands/" + original.path("id").asLong() + "/retry")
                            .header("satoken", token))
                    .andExpect(status().isCreated()).andReturn();
            JsonNode retry = json.readTree(retried.getResponse().getContentAsByteArray());
            assertThat(retry.path("id").asLong()).isNotEqualTo(original.path("id").asLong());
            assertThat(retry.path("retryOfId").asLong()).isEqualTo(original.path("id").asLong());
            assertThat(retry.path("serialNo").asInt()).isNotEqualTo(original.path("serialNo").asInt());
            assertThat(command(token, original.path("id").asLong())
                    .path("communication").path("status").asText()).isEqualTo("FAILED");

            Frame secondDownlink = receive(socket);
            acknowledge(socket, bound.terminalNo(), 910, secondDownlink.header().serialNumber(), 0x8300, 0);
            awaitStatus(token, retry.path("id").asLong(), "ACK_SUCCESS");
            expectError(post("/api/v1/commands/" + retry.path("id").asLong() + "/retry"), token, null,
                    409, "COMMAND_NOT_RETRYABLE");
        }
    }

    @Test
    void listShowsSubmissionAndCommunicationSeparatelyWithinScope() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        BoundTerminal bound = createBoundTerminal(token);
        try (Socket socket = connect()) {
            authenticate(socket, bound, register(socket, bound, 1), 2);
            JsonNode created = submitText(token, bound.terminalId(), "列表文本", null);
            Frame downlink = receive(socket);
            acknowledge(socket, bound.terminalNo(), 911, downlink.header().serialNumber(), 0x8300, 0);
            awaitStatus(token, created.path("id").asLong(), "ACK_SUCCESS");

            var response = mvc.perform(get("/api/v1/commands?terminalId=" + bound.terminalId())
                            .header("satoken", token))
                    .andExpect(status().isOk()).andReturn();
            JsonNode rows = json.readTree(response.getResponse().getContentAsByteArray());
            assertThat(rows.isArray()).isTrue();
            assertThat(rows.size()).isEqualTo(1);
            JsonNode row = rows.get(0);
            assertThat(row.path("submission").path("status").asText()).isEqualTo("SUBMITTED");
            assertThat(row.path("submission").path("operatorName").asText()).isEqualTo(ADMIN);
            assertThat(row.path("submission").path("submittedAt").isNull()).isFalse();
            assertThat(row.path("communication").path("status").asText()).isEqualTo("ACK_SUCCESS");
            assertThat(row.path("commandType").asText()).isEqualTo("TEXT");
        }
    }

    private JsonNode submitText(String token, long terminalId, String text, Integer textType) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("terminalId", terminalId);
        body.put("text", text);
        if (textType != null) {
            body.put("textType", textType);
        }
        var response = mvc.perform(post("/api/v1/commands/text").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray());
    }

    private JsonNode submitTracking(String token, long terminalId, int interval, long valid) throws Exception {
        var response = mvc.perform(post("/api/v1/commands/tracking").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                                Map.of("terminalId", terminalId, "intervalSeconds", interval,
                                        "validSeconds", valid))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray());
    }

    private void expectError(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                             String token, Map<String, Object> body, int status, String code) throws Exception {
        builder.header("satoken", token);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        var response = mvc.perform(builder).andExpect(status().is(status)).andReturn();
        assertThat(response.getResponse().getContentAsString()).contains(code);
    }

    private JsonNode command(String token, long id) throws Exception {
        var response = mvc.perform(get("/api/v1/commands/" + id).header("satoken", token))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray());
    }

    private JsonNode awaitStatus(String token, long id, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = command(token, id);
            if (expected.equals(last.path("communication").path("status").asText())) {
                return last;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("command " + id + " did not reach " + expected + ": " + last);
    }

    private void awaitAudit(String action, String target) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            if (auditCount(action, target) > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("audit " + action + " for " + target + " was not recorded");
    }

    private int auditCount(String action, String target) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE category = 'COMMAND' AND action = ? AND target = ?
                """, Integer.class, action, target);
    }

    private BoundTerminal createBoundTerminal(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = create(token, "/fleets", Map.of("fleetCode", "D" + suffix, "fleetName", "指令车队"));
        return createBoundTerminal(token, fleet, suffix);
    }

    private BoundTerminal createBoundTerminal(String token, long fleet, String suffix) throws Exception {
        String terminalNo = "013800139000" + String.format("%08d", TERMINAL_SEQUENCE.getAndIncrement());
        long vehicle = create(token, "/vehicles", Map.of("plateNo", "指令" + suffix, "fleetId", fleet));
        long terminal = create(token, "/terminals", Map.of("terminalNo", terminalNo));
        mvc.perform(put("/api/v1/vehicles/" + vehicle + "/terminal").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("terminalId", terminal))))
                .andExpect(status().isOk());
        return new BoundTerminal(terminal, vehicle, terminalNo, suffix);
    }

    private String register(Socket socket, BoundTerminal bound, int serial) throws Exception {
        send(socket, frame(bound.terminalNo(), 0x0100, serial,
                new TerminalRegister(1, 1, "ABC12", "TESTMODEL", bound.terminalNo(), 2,
                        "湘A" + bound.suffix()).encode()));
        TerminalRegisterResponse response = TerminalRegisterResponse.parse(receive(socket).body());
        assertThat(response.result()).isZero();
        return response.authCode();
    }

    private void authenticate(Socket socket, BoundTerminal bound, String authCode, int serial) throws Exception {
        send(socket, frame(bound.terminalNo(), 0x0102, serial,
                new TerminalAuthentication(authCode, IMEI, VERSION).encode()));
        TerminalGeneralResponse response = TerminalGeneralResponse.parse(receive(socket).body());
        assertThat(response.responseId()).isEqualTo(0x0102);
        assertThat(response.result()).isZero();
    }

    private void acknowledge(Socket socket, String terminalNo, int frameSerial, int responseSerial,
                             int responseId, int result) throws Exception {
        send(socket, frame(terminalNo, 0x0001, frameSerial,
                new TerminalGeneralResponse(responseSerial, responseId, result).encode()));
    }

    private Socket connect() throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", access.port()), 5000);
        socket.setSoTimeout(3000);
        return socket;
    }

    private Frame frame(String terminalNo, int messageId, int serial, byte[] body) {
        return new Frame(new MessageHeader(messageId, BodyProperties.plain(body.length), 1, terminalNo, serial), body);
    }

    private void send(Socket socket, Frame frame) throws Exception {
        socket.getOutputStream().write(FrameCodec.encode(frame));
        socket.getOutputStream().flush();
    }

    private Frame receive(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[4096];
        int length = 0;
        while (true) {
            int read = in.read(buffer, length, buffer.length - length);
            if (read < 0) {
                throw new IllegalStateException("TCP listener closed before response");
            }
            length += read;
            var frames = FrameCodec.splitFrames(Arrays.copyOf(buffer, length));
            if (!frames.isEmpty()) {
                return FrameCodec.decode(frames.getFirst());
            }
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

    private static String hex(byte[] body) {
        StringBuilder out = new StringBuilder();
        for (byte value : body) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(String.format("%02X", value & 0xFF));
        }
        return out.toString();
    }

    private record BoundTerminal(long terminalId, long vehicleId, String terminalNo, String suffix) { }

    @org.springframework.boot.test.context.TestConfiguration
    static class TimeConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.vms.support.MutableClock commandClock() {
            return new com.vms.support.MutableClock(java.time.Instant.parse("2026-11-09T02:00:00Z"),
                    java.time.ZoneId.of("Asia/Shanghai"));
        }
    }
}
