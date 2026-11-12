package com.vms.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        "vms.bootstrap-admin.username=terminal-access-admin",
        "vms.bootstrap-admin.password=Terminal-Access-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0"
})
@Import({IntegrationContainers.class, TerminalTcpIntegrationTest.TimeConfiguration.class})
@AutoConfigureMockMvc
class TerminalTcpIntegrationTest {

    private static final String TERMINAL_NO = "01380013800000000001";
    private static final String IMEI = "012345678901234";
    private static final String VERSION = "00000000000000000001";
    private static final AtomicInteger TERMINAL_SEQUENCE = new AtomicInteger(2);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TerminalAccessPort access;
    @Autowired com.vms.support.MutableClock clock;

    @Test
    void registeredBoundTerminalCompletesRegisterAuthenticationHeartbeatAndLogoutOverTcp() throws Exception {
        String token = login();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = create(token, "/fleets", Map.of("fleetCode", "A" + suffix, "fleetName", "接入车队"));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", "接入" + suffix, "fleetId", fleet));
        long terminal = create(token, "/terminals", Map.of("terminalNo", TERMINAL_NO));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/v1/vehicles/" + vehicle + "/terminal")
                        .header("satoken", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("terminalId", terminal))))
                .andExpect(status().isOk());
        assertThat(terminalView(token, terminal).path("communicationStatus").asText()).isEqualTo("NOT_CONNECTED");

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", access.port()), 5000);
            socket.setSoTimeout(3000);
            send(socket, frame(0x0100, 1, new TerminalRegister(1, 1, "ABC12", "TESTMODEL",
                    TERMINAL_NO, 2, "湘A" + suffix).encode()));
            Frame registerResponse = receive(socket);
            TerminalRegisterResponse registered = TerminalRegisterResponse.parse(registerResponse.body());
            assertThat(registerResponse.header().messageId()).isEqualTo(0x8100);
            assertThat(registered.result()).isZero();
            assertThat(registered.authCode()).isNotBlank();

            send(socket, frame(0x0102, 2, new TerminalAuthentication(registered.authCode(), IMEI, VERSION).encode()));
            Frame authenticationResponse = receive(socket);
            TerminalGeneralResponse authenticated = TerminalGeneralResponse.parse(authenticationResponse.body());
            assertThat(authenticationResponse.header().messageId()).isEqualTo(0x8001);
            assertThat(authenticated.responseId()).isEqualTo(0x0102);
            assertThat(authenticated.result()).isZero();

            send(socket, frame(0x0002, 3, new byte[0]));
            Frame heartbeatResponse = receive(socket);
            TerminalGeneralResponse heartbeat = TerminalGeneralResponse.parse(heartbeatResponse.body());
            assertThat(heartbeat.responseId()).isEqualTo(0x0002);
            assertThat(heartbeat.result()).isZero();
            assertThat(jdbc.queryForObject("SELECT ever_authenticated FROM terminal WHERE id = ?", Boolean.class, terminal))
                    .isTrue();
            assertThat(jdbc.queryForObject("SELECT last_seen_at IS NOT NULL FROM terminal WHERE id = ?", Boolean.class, terminal))
                    .isTrue();
            assertThat(jdbc.queryForObject("SELECT auth_secret_hash FROM terminal WHERE id = ?", String.class, terminal))
                    .startsWith("$2");
            assertThat(terminalView(token, terminal).path("communicationStatus").asText()).isEqualTo("ONLINE");

            send(socket, frame(0x0003, 4, new byte[0]));
            Frame logoutResponse = receive(socket);
            TerminalGeneralResponse logout = TerminalGeneralResponse.parse(logoutResponse.body());
            assertThat(logout.responseId()).isEqualTo(0x0003);
            assertThat(logout.result()).isZero();
            assertThat(access.current(TERMINAL_NO)).isEmpty();
            assertThat(terminalView(token, terminal).path("communicationStatus").asText()).isEqualTo("OFFLINE");
        }
    }

    @Test
    void unboundTerminalIsRejectedWithoutCreatingConnectionOrSecret() throws Exception {
        String token = login();
        String terminalNo = terminalNo();
        long terminal = create(token, "/terminals", Map.of("terminalNo", terminalNo));
        try (Socket socket = connect()) {
            send(socket, frame(terminalNo, 0x0100, 1,
                    new TerminalRegister(1, 1, "ABC12", "TESTMODEL", terminalNo, 2, "未绑定").encode()));
            TerminalRegisterResponse response = TerminalRegisterResponse.parse(receive(socket).body());
            assertThat(response.result()).isEqualTo(TerminalAccessService.REGISTER_REJECTED);
            assertThat(jdbc.queryForObject("SELECT auth_secret_hash IS NULL FROM terminal WHERE id = ?", Boolean.class, terminal))
                    .isTrue();
            assertThat(access.current(terminalNo)).isEmpty();
        }
    }

    @Test
    void unknownTerminalIsRejectedWithoutCreatingTerminalRecord() throws Exception {
        String unknownTerminalNo = terminalNo();
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM terminal WHERE terminal_no = ?", Integer.class, unknownTerminalNo);
        try (Socket socket = connect()) {
            send(socket, frame(unknownTerminalNo, 0x0100, 1,
                    new TerminalRegister(1, 1, "ABC12", "TESTMODEL", unknownTerminalNo, 2, "未知终端").encode()));
            TerminalRegisterResponse response = TerminalRegisterResponse.parse(receive(socket).body());
            assertThat(response.result()).isEqualTo(TerminalAccessService.REGISTER_REJECTED);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM terminal WHERE terminal_no = ?", Integer.class, unknownTerminalNo))
                    .isEqualTo(before);
            assertThat(access.current(unknownTerminalNo)).isEmpty();
        }
    }

    @Test
    void disabledTerminalIsRejectedBeforeAuthentication() throws Exception {
        String token = login();
        String terminalNo = terminalNo();
        long terminal = create(token, "/terminals", Map.of("terminalNo", terminalNo));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/terminals/" + terminal)
                        .header("satoken", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isOk());
        try (Socket socket = connect()) {
            send(socket, frame(terminalNo, 0x0100, 1,
                    new TerminalRegister(1, 1, "ABC12", "TESTMODEL", terminalNo, 2, "停用终端").encode()));
            TerminalRegisterResponse response = TerminalRegisterResponse.parse(receive(socket).body());
            assertThat(response.result()).isEqualTo(TerminalAccessService.REGISTER_REJECTED);
            assertThat(jdbc.queryForObject("SELECT auth_secret_hash IS NULL FROM terminal WHERE id = ?", Boolean.class, terminal))
                    .isTrue();
            assertThat(access.current(terminalNo)).isEmpty();
        }
    }

    @Test
    void wrongAuthenticationDoesNotMarkTerminalAsConnected() throws Exception {
        String token = login();
        BoundTerminal bound = createBoundTerminal(token);
        try (Socket socket = connect()) {
            String authCode = register(socket, bound.terminalNo(), bound.suffix(), 1);
            send(socket, frame(bound.terminalNo(), 0x0102, 2,
                    new TerminalAuthentication(authCode + "wrong", IMEI, VERSION).encode()));
            TerminalGeneralResponse response = TerminalGeneralResponse.parse(receive(socket).body());
            assertThat(response.responseId()).isEqualTo(0x0102);
            assertThat(response.result()).isEqualTo(TerminalAccessService.AUTHENTICATION_FAILED);
            assertThat(access.current(bound.terminalNo())).isEmpty();
            assertThat(jdbc.queryForObject("SELECT ever_authenticated FROM terminal WHERE id = ?", Boolean.class, bound.terminalId()))
                    .isFalse();
            assertThat(jdbc.queryForObject("SELECT last_seen_at IS NULL FROM terminal WHERE id = ?", Boolean.class, bound.terminalId()))
                    .isTrue();
        }
    }

    @Test
    void newerAuthenticatedConnectionReplacesOlderEpochAndChannel() throws Exception {
        String token = login();
        BoundTerminal bound = createBoundTerminal(token);
        try (Socket first = connect(); Socket second = connect()) {
            String firstAuth = register(first, bound.terminalNo(), bound.suffix(), 1);
            authenticate(first, bound.terminalNo(), firstAuth, 2);
            TerminalAccessPort.SessionView firstSession = access.current(bound.terminalNo()).orElseThrow();

            String secondAuth = register(second, bound.terminalNo(), bound.suffix(), 3);
            authenticate(second, bound.terminalNo(), secondAuth, 4);
            TerminalAccessPort.SessionView secondSession = access.current(bound.terminalNo()).orElseThrow();

            assertThat(secondSession.epoch()).isGreaterThan(firstSession.epoch());
            assertThat(secondSession.writable()).isTrue();
            assertThat(firstSession.epoch()).isNotEqualTo(secondSession.epoch());
            first.setSoTimeout(3000);
            assertThat(first.getInputStream().read()).isEqualTo(-1);

            send(second, frame(bound.terminalNo(), 0x0002, 5, new byte[0]));
            TerminalGeneralResponse heartbeat = TerminalGeneralResponse.parse(receive(second).body());
            assertThat(heartbeat.responseId()).isEqualTo(0x0002);
            assertThat(access.current(bound.terminalNo()).orElseThrow().epoch()).isEqualTo(secondSession.epoch());
        }
    }

    @Test
    void malformedRegistrationBodyIsRejectedWithoutResponse() throws Exception {
        String terminalNo = terminalNo();
        try (Socket socket = connect()) {
            send(socket, frame(terminalNo, 0x0100, 1, new byte[0]));
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
            assertThat(access.current(terminalNo)).isEmpty();
        }
    }

    @Test
    void registrationRejectsBodyTerminalIdentityMismatch() throws Exception {
        String headerTerminalNo = terminalNo();
        String bodyTerminalNo = terminalNo();
        try (Socket socket = connect()) {
            send(socket, frame(headerTerminalNo, 0x0100, 1,
                    new TerminalRegister(1, 1, "ABC12", "TESTMODEL", bodyTerminalNo, 2, "身份不一致").encode()));
            TerminalRegisterResponse response = TerminalRegisterResponse.parse(receive(socket).body());
            assertThat(response.result()).isEqualTo(TerminalAccessService.REGISTER_REJECTED);
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
            assertThat(access.current(headerTerminalNo)).isEmpty();
        }
    }

    @Test
    void terminalGeneralResponseUpdatesLastSeenAt() throws Exception {
        String token = login();
        BoundTerminal bound = createBoundTerminal(token);
        try (Socket socket = connect()) {
            String authCode = register(socket, bound.terminalNo(), bound.suffix(), 1);
            authenticate(socket, bound.terminalNo(), authCode, 2);
            java.time.LocalDateTime before = jdbc.queryForObject(
                    "SELECT last_seen_at FROM terminal WHERE id = ?", java.time.LocalDateTime.class, bound.terminalId());
            clock.advance(java.time.Duration.ofSeconds(1));
            send(socket, frame(bound.terminalNo(), 0x0001, 3,
                    new TerminalGeneralResponse(2, 0x0002, 0).encode()));
            send(socket, frame(bound.terminalNo(), 0x0002, 4, new byte[0]));
            receive(socket);
            java.time.LocalDateTime after = jdbc.queryForObject(
                    "SELECT last_seen_at FROM terminal WHERE id = ?", java.time.LocalDateTime.class, bound.terminalId());
            assertThat(after).isAfter(before);
        }
    }

    @Test
    void terminalBecomesOfflineAfterMoreThan180SecondsWithoutCommunication() throws Exception {
        String token = login();
        BoundTerminal bound = createBoundTerminal(token);
        try (Socket socket = connect()) {
            String authCode = register(socket, bound.terminalNo(), bound.suffix(), 1);
            authenticate(socket, bound.terminalNo(), authCode, 2);
            assertThat(terminalView(token, bound.terminalId()).path("communicationStatus").asText()).isEqualTo("ONLINE");
            clock.advance(java.time.Duration.ofSeconds(181));
            assertThat(terminalView(token, bound.terminalId()).path("communicationStatus").asText()).isEqualTo("OFFLINE");
        }
    }

    @Test
    void disablingTerminalImmediatelyClosesAuthenticatedChannel() throws Exception {
        String token = login();
        BoundTerminal bound = createBoundTerminal(token);
        try (Socket socket = connect()) {
            String authCode = register(socket, bound.terminalNo(), bound.suffix(), 1);
            authenticate(socket, bound.terminalNo(), authCode, 2);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                            "/api/v1/terminals/" + bound.terminalId())
                            .header("satoken", token).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("status", "DISABLED"))))
                    .andExpect(status().isOk());
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
            assertThat(access.current(bound.terminalNo())).isEmpty();
        }
    }

    @Test
    void unbindingTerminalImmediatelyClosesAuthenticatedChannel() throws Exception {
        String token = login();
        BoundTerminal bound = createBoundTerminal(token);
        try (Socket socket = connect()) {
            String authCode = register(socket, bound.terminalNo(), bound.suffix(), 1);
            authenticate(socket, bound.terminalNo(), authCode, 2);
            clock.advance(java.time.Duration.ofSeconds(1));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                            "/api/v1/vehicles/" + bound.vehicleId() + "/terminal")
                            .header("satoken", token).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"terminalId\":null}"))
                    .andExpect(status().isOk());
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
            assertThat(access.current(bound.terminalNo())).isEmpty();
        }
    }

    @Test
    void nonActiveDisconnectRemovesCurrentRegistrySession() throws Exception {
        String token = login();
        BoundTerminal bound = createBoundTerminal(token);
        Socket socket = connect();
        String authCode = register(socket, bound.terminalNo(), bound.suffix(), 1);
        authenticate(socket, bound.terminalNo(), authCode, 2);
        assertThat(access.current(bound.terminalNo())).isPresent();
        socket.close();
        awaitNoSession(bound.terminalNo());
    }
    private BoundTerminal createBoundTerminal(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String terminalNo = terminalNo();
        long fleet = create(token, "/fleets", Map.of("fleetCode", "B" + suffix, "fleetName", "TCP准入车队"));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", "TCP" + suffix, "fleetId", fleet));
        long terminal = create(token, "/terminals", Map.of("terminalNo", terminalNo));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/v1/vehicles/" + vehicle + "/terminal")
                        .header("satoken", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("terminalId", terminal))))
                .andExpect(status().isOk());
        return new BoundTerminal(terminal, vehicle, terminalNo, suffix);
    }

    private String register(Socket socket, String terminalNo, String suffix, int serial) throws Exception {
        send(socket, frame(terminalNo, 0x0100, serial,
                new TerminalRegister(1, 1, "ABC12", "TESTMODEL", terminalNo, 2, "湘A" + suffix).encode()));
        TerminalRegisterResponse response = TerminalRegisterResponse.parse(receive(socket).body());
        assertThat(response.result()).isZero();
        assertThat(response.authCode()).isNotBlank();
        return response.authCode();
    }

    private void authenticate(Socket socket, String terminalNo, String authCode, int serial) throws Exception {
        send(socket, frame(terminalNo, 0x0102, serial,
                new TerminalAuthentication(authCode, IMEI, VERSION).encode()));
        TerminalGeneralResponse response = TerminalGeneralResponse.parse(receive(socket).body());
        assertThat(response.responseId()).isEqualTo(0x0102);
        assertThat(response.result()).isZero();
    }

    private Socket connect() throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", access.port()), 5000);
        socket.setSoTimeout(3000);
        return socket;
    }

    private String terminalNo() {
        return "013800138000" + String.format("%08d", TERMINAL_SEQUENCE.getAndIncrement());
    }
    private Frame frame(int messageId, int serial, byte[] body) {
        return frame(TERMINAL_NO, messageId, serial, body);
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
            int read;
            try {
                read = in.read(buffer, length, buffer.length - length);
            } catch (java.net.SocketTimeoutException e) {
                throw new IllegalStateException("TCP response timed out", e);
            }
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

    private void awaitNoSession(String terminalNo) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline && access.current(terminalNo).isPresent()) {
            Thread.sleep(10);
        }
        assertThat(access.current(terminalNo)).isEmpty();
    }
    private JsonNode terminalView(String token, long id) throws Exception {
        var response = mvc.perform(get("/api/v1/terminals/" + id).header("satoken", token))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray());
    }

    private String login() throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "terminal-access-admin",
                                "password", "Terminal-Access-Pass-2026"))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray()).path("token").asText();
    }

    private long create(String token, String path, Object body) throws Exception {
        var response = mvc.perform(post("/api/v1" + path).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode node = json.readTree(response.getResponse().getContentAsByteArray());
        return node.path("id").asLong();
    }

    private record BoundTerminal(long terminalId, long vehicleId, String terminalNo, String suffix) { }

    @org.springframework.boot.test.context.TestConfiguration
    static class TimeConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.vms.support.MutableClock accessClock() {
            return new com.vms.support.MutableClock(java.time.Instant.parse("2026-11-09T02:00:00Z"),
                    java.time.ZoneId.of("Asia/Shanghai"));
        }
    }
}
