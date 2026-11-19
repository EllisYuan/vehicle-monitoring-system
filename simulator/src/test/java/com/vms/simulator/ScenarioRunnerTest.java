package com.vms.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.vms.protocol.BodyProperties;
import com.vms.protocol.Frame;
import com.vms.protocol.FrameCodec;
import com.vms.protocol.MessageHeader;
import com.vms.protocol.PlatformGeneralResponse;
import com.vms.protocol.PositionReport;
import com.vms.protocol.TerminalRegisterResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 22：用本地假平台验证 ACK 等待/重试/有界退出与两类记录，不依赖真实服务器。
 * 真实 TCP + MySQL 的联合验证在 22 的验收脚本中执行。
 */
class ScenarioRunnerTest {

    @TempDir
    Path outputDir;

    @Test
    void ackLossRetriesSameSerialAndWritesSeparateSendAndAckRecords() throws Exception {
        try (FakePlatform platform = new FakePlatform(ignored -> Reply.okReply())) {
            ScenarioConfig config = ScenarioConfig.parse("""
                    seed=11
                    vehicles=01380022000000000001
                    host=127.0.0.1
                    port=%d
                    interval-ms=100
                    ack-timeout-ms=200
                    ack-max-retries=3
                    scene.ack-loss=true
                    """.formatted(platform.port()));

            RunSummary summary = ScenarioRunner.run(config, outputDir);

            assertThat(summary.success()).isTrue();
            assertThat(summary.terminals()).singleElement()
                    .satisfies(terminal -> assertThat(terminal.status()).isEqualTo("OK"));
            assertThat(summary.sendLog()).isNotEqualTo(summary.ackLog());
            assertThat(summary.sendLog().getFileName().toString())
                    .contains("22-").contains(String.valueOf(config.seed())).endsWith("-send.tsv");
            assertThat(summary.ackLog().getFileName().toString()).endsWith("-ack.tsv");

            List<Map<String, String>> sends = readTsv(summary.sendLog());
            List<Map<String, String>> acks = readTsv(summary.ackLog());
            List<Map<String, String>> positions = sends.stream()
                    .filter(row -> "0200".equals(row.get("message_id"))).toList();
            assertThat(positions).hasSize(3);

            String firstSerial = positions.getFirst().get("serial");
            List<Map<String, String>> firstSerialSends = positions.stream()
                    .filter(row -> firstSerial.equals(row.get("serial"))).toList();
            assertThat(firstSerialSends).extracting(row -> row.get("attempt")).containsExactly("1", "2");
            assertThat(firstSerialSends).extracting(row -> row.get("drop_acks")).containsExactly("1", "1");

            List<Map<String, String>> firstSerialAcks = acks.stream()
                    .filter(row -> firstSerialSends.getFirst().get("send_id").equals(row.get("send_id"))
                            || firstSerialSends.get(1).get("send_id").equals(row.get("send_id")))
                    .toList();
            assertThat(firstSerialAcks).extracting(row -> row.get("status"))
                    .containsExactly("ACK_DROPPED", "ACK_SUCCESS");

            assertThat(positions).allSatisfy(send -> assertThat(acks)
                    .anySatisfy(ack -> {
                        assertThat(ack.get("send_id")).isEqualTo(send.get("send_id"));
                        assertThat(ack.get("terminal")).isEqualTo(send.get("terminal"));
                        assertThat(ack.get("serial")).isEqualTo(send.get("serial"));
                    }));
            assertThat(acks).allSatisfy(ack -> assertThat(sends)
                    .anySatisfy(send -> assertThat(send.get("send_id")).isEqualTo(ack.get("send_id"))));
        }
    }

    @Test
    void permanentRejectionStopsRetriesAndRecordsReason() throws Exception {
        try (FakePlatform platform = new FakePlatform(ignored -> Reply.rejectReply())) {
            ScenarioConfig config = ScenarioConfig.parse("""
                    seed=12
                    vehicles=01380022000000000001
                    host=127.0.0.1
                    port=%d
                    interval-ms=100
                    ack-timeout-ms=200
                    ack-max-retries=3
                    scene.ack-loss=true
                    """.formatted(platform.port()));

            RunSummary summary = ScenarioRunner.run(config, outputDir);

            assertThat(summary.success()).isFalse();
            assertThat(summary.terminals()).singleElement().satisfies(terminal -> {
                assertThat(terminal.status()).isEqualTo("FAILED");
                assertThat(terminal.failureReason()).contains("result=1");
            });
            List<Map<String, String>> sends = readTsv(summary.sendLog());
            List<Map<String, String>> positions = sends.stream()
                    .filter(row -> "0200".equals(row.get("message_id"))).toList();
            assertThat(positions).hasSize(2);
            assertThat(positions).extracting(row -> row.get("attempt")).containsOnly("1");
            List<Map<String, String>> acks = readTsv(summary.ackLog());
            assertThat(acks).filteredOn(row -> "ACK_REJECTED".equals(row.get("status")))
                    .extracting(row -> row.get("send_id"))
                    .containsExactlyInAnyOrder(positions.get(0).get("send_id"), positions.get(1).get("send_id"));
            assertThat(acks).filteredOn(row -> "NO_ACK".equals(row.get("status"))).isEmpty();
        }
    }

    @Test
    void exhaustedAckRetriesAreBoundedAndRecorded() throws Exception {
        try (FakePlatform platform = new FakePlatform(ignored -> Reply.noReply())) {
            ScenarioConfig config = ScenarioConfig.parse("""
                    seed=13
                    vehicles=01380022000000000001
                    host=127.0.0.1
                    port=%d
                    interval-ms=100
                    ack-timeout-ms=100
                    ack-max-retries=2
                    scene.ack-loss=true
                    """.formatted(platform.port()));

            long started = System.nanoTime();
            RunSummary summary = ScenarioRunner.run(config, outputDir);
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(elapsedMs).isLessThan(5000);
            assertThat(summary.success()).isFalse();
            assertThat(summary.terminals()).singleElement().satisfies(terminal -> {
                assertThat(terminal.status()).isEqualTo("FAILED");
                assertThat(terminal.failureReason()).contains("ACK 超时");
            });
            List<Map<String, String>> positions = readTsv(summary.sendLog()).stream()
                    .filter(row -> "0200".equals(row.get("message_id"))).toList();
            assertThat(positions).hasSize(6);
            assertThat(positions).extracting(row -> row.get("attempt"))
                    .containsExactly("1", "2", "3", "1", "2", "3");
            Set<String> positionSendIds = positions.stream().map(row -> row.get("send_id"))
                    .collect(java.util.stream.Collectors.toSet());
            List<Map<String, String>> positionAcks = readTsv(summary.ackLog()).stream()
                    .filter(row -> positionSendIds.contains(row.get("send_id"))).toList();
            assertThat(positionAcks).hasSize(6)
                    .allSatisfy(row -> assertThat(row.get("status")).isEqualTo("NO_ACK"));
        }
    }

    @Test
    void positionTimesFollowPlannedTimelineInsteadOfHostClock() throws Exception {
        try (FakePlatform platform = new FakePlatform(ignored -> Reply.okReply())) {
            ScenarioConfig config = ScenarioConfig.parse("""
                    seed=15
                    vehicles=01380022000000000001
                    host=127.0.0.1
                    port=%d
                    interval-ms=1000
                    ack-timeout-ms=200
                    scene.normal=true
                    """.formatted(platform.port()));
            LocalDateTime scenarioStart = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

            RunSummary summary = ScenarioRunner.run(config, outputDir, scenarioStart);

            assertThat(summary.success()).isTrue();
            // 终端上报时间取计划时间线：平台 (terminal_id, gps_time) 幂等语义因此只受计划控制，
            // 不会因调度抖动把相邻计划点压进同一秒而随机丢点。
            List<LocalDateTime> times = platform.received.stream()
                    .filter(frame -> frame.header().messageId() == 0x0200)
                    .map(frame -> PositionReport.parse(frame.body()).time())
                    .toList();
            assertThat(times).containsExactly(scenarioStart, scenarioStart.plusSeconds(1),
                    scenarioStart.plusSeconds(2), scenarioStart.plusSeconds(3), scenarioStart.plusSeconds(4));
            // 性能验收按 manifest 的 scenario_start 与 duration_ms 还原计划时间线并核对持久行。
            assertThat(Files.readString(summary.manifest()))
                    .contains("scenario_start=2026-01-02T03:04:05")
                    .contains("duration_ms=0");
        }
    }

    @Test
    void unexpectedDisconnectAbortsTerminalWithoutInfiniteRetry() throws Exception {
        try (FakePlatform platform = new FakePlatform(ignored -> Reply.closeReply())) {
            ScenarioConfig config = ScenarioConfig.parse("""
                    seed=14
                    vehicles=01380022000000000001
                    host=127.0.0.1
                    port=%d
                    interval-ms=100
                    ack-timeout-ms=2000
                    ack-max-retries=3
                    scene.normal=true
                    """.formatted(platform.port()));

            long started = System.nanoTime();
            RunSummary summary = ScenarioRunner.run(config, outputDir);
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(elapsedMs).isLessThan(5000);
            assertThat(summary.success()).isFalse();
            assertThat(summary.terminals()).singleElement().satisfies(terminal -> {
                assertThat(terminal.status()).isEqualTo("ABORTED");
                assertThat(terminal.failureReason()).contains("UNEXPECTED_DISCONNECT");
                assertThat(terminal.remainingEntries()).isPositive();
            });
            List<Map<String, String>> positions = readTsv(summary.sendLog()).stream()
                    .filter(row -> "0200".equals(row.get("message_id"))).toList();
            assertThat(positions).hasSize(1);
            assertThat(readTsv(summary.ackLog()))
                    .filteredOn(row -> "DISCONNECTED".equals(row.get("status")))
                    .extracting(row -> row.get("send_id"))
                    .containsExactly(positions.getFirst().get("send_id"));
        }
    }
    private static List<Map<String, String>> readTsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        String[] header = lines.getFirst().split("\t", -1);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] cells = lines.get(i).split("\t", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int column = 0; column < header.length; column++) {
                row.put(header[column], column < cells.length ? cells[column] : "");
            }
            rows.add(row);
        }
        return rows;
    }

    /** 假平台的应答策略：ok / reject / silent。 */
    private record Reply(int result, boolean silent, boolean closeConnection) {
        static Reply okReply() {
            return new Reply(0, false, false);
        }

        static Reply rejectReply() {
            return new Reply(1, false, false);
        }

        static Reply noReply() {
            return new Reply(0, true, false);
        }

        static Reply closeReply() {
            return new Reply(0, true, true);
        }
    }

    /**
     * 本地 TCP 假平台：完成注册/鉴权应答，并按策略应答位置、批量、心跳与注销。
     * 记录收到的每个帧，便于核对终端实际发送内容。
     */
    static final class FakePlatform implements AutoCloseable {

        private final ServerSocket server;
        private final Thread acceptThread;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Function<Frame, Reply> positionPolicy;
        final List<Frame> received = new CopyOnWriteArrayList<>();

        FakePlatform(Function<Frame, Reply> positionPolicy) throws IOException {
            this.positionPolicy = positionPolicy;
            this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            this.acceptThread = Thread.ofPlatform().name("fake-platform-accept").start(this::acceptLoop);
        }

        int port() {
            return server.getLocalPort();
        }

        private void acceptLoop() {
            while (!closed.get()) {
                try {
                    Socket socket = server.accept();
                    Thread.ofPlatform().name("fake-platform-conn").start(() -> serve(socket));
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void serve(Socket socket) {
            try (socket) {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                while (!closed.get()) {
                    Frame frame = nextFrame(input);
                    if (frame == null) {
                        return;
                    }
                    received.add(frame);
                    if (!handle(frame, output, socket)) {
                        return;
                    }
                }
            } catch (IOException e) {
                // 连接正常结束；测试断言只看已收到的帧与终端状态。
            }
        }

        private boolean handle(Frame frame, OutputStream output, Socket socket) throws IOException {
            int messageId = frame.header().messageId();
            int serial = frame.header().serialNumber();
            switch (messageId) {
                case 0x0100 -> write(output, reply(frame, 0x8100,
                        new TerminalRegisterResponse(serial, TerminalRegisterResponse.RESULT_SUCCESS,
                                "AUTH01").encode()));
                case 0x0102, 0x0002, 0x0003 -> write(output, reply(frame, 0x8001,
                        new PlatformGeneralResponse(serial, messageId, 0).encode()));
                case 0x0200, 0x0704 -> {
                    Reply decision = positionPolicy.apply(frame);
                    if (decision.closeConnection()) {
                        socket.close();
                        return false;
                    }
                    if (!decision.silent()) {
                        write(output, reply(frame, 0x8001,
                                new PlatformGeneralResponse(serial, messageId, decision.result()).encode()));
                    }
                }
                default -> {
                    // 0x0001 终端应答无需回帧。
                }
            }
            return true;
        }

        private Frame reply(Frame request, int messageId, byte[] body) {
            return new Frame(new MessageHeader(messageId, BodyProperties.plain(body.length), 1,
                    request.header().terminalPhone(), 1), body);
        }

        private void write(OutputStream output, Frame frame) throws IOException {
            output.write(FrameCodec.encode(frame));
            output.flush();
        }

        private Frame nextFrame(InputStream input) throws IOException {
            byte[] buffer = new byte[8192];
            int length = 0;
            boolean started = false;
            while (true) {
                int read = input.read();
                if (read < 0) {
                    return null;
                }
                if ((read & 0xFF) == 0x7E) {
                    if (started) {
                        buffer[length++] = (byte) read;
                        return FrameCodec.decode(Arrays.copyOf(buffer, length));
                    }
                    started = true;
                    length = 0;
                    buffer[length++] = (byte) read;
                } else if (started) {
                    buffer[length++] = (byte) read;
                }
            }
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            server.close();
        }
    }
}





