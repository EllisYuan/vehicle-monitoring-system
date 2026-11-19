package com.vms.simulator;

import com.vms.protocol.BodyProperties;
import com.vms.protocol.Frame;
import com.vms.protocol.FrameCodec;
import com.vms.protocol.MessageHeader;
import com.vms.protocol.PlatformGeneralResponse;
import com.vms.protocol.PositionBatch;
import com.vms.protocol.PositionReport;
import com.vms.protocol.TemporaryTracking;
import com.vms.protocol.TerminalAuthentication;
import com.vms.protocol.TerminalGeneralResponse;
import com.vms.protocol.TerminalRegister;
import com.vms.protocol.TerminalRegisterResponse;
import com.vms.protocol.TextIssue;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 22：配置驱动的多车场景编排执行器。
 *
 * <p>只按计划编排连接、断开、位置/批量/注销报文并等待应答，不在模拟器内复刻平台判定。
 * 发送记录与 ACK/应答记录分别写入两个 TSV；每次发送尝试都有唯一 send_id，ACK 记录以
 * send_id + terminal + serial 与之对齐。ACK 等待上限、重试次数与放弃条件全部来自配置，
 * 连接异常时终止该终端并记录原因，不做无限重连。
 */
public final class ScenarioRunner {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int POLL_MS = 200;
    private static final ZoneId POSITION_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String IMEI = "012345678901234";
    private static final String VERSION = "00000000000000000001";

    private ScenarioRunner() {
    }

    public static RunSummary run(ScenarioConfig config, Path outputDir) throws IOException {
        return run(config, outputDir, LocalDateTime.now(POSITION_ZONE));
    }

    static RunSummary run(ScenarioConfig config, Path outputDir, LocalDateTime scenarioStart) throws IOException {
        Files.createDirectories(outputDir);
        String runId = "22-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
                + "-seed" + config.seed();
        Path sendLog = outputDir.resolve(runId + "-send.tsv");
        Path ackLog = outputDir.resolve(runId + "-ack.tsv");
        Path summaryFile = outputDir.resolve(runId + "-summary.tsv");
        Path manifest = outputDir.resolve(runId + "-manifest.txt");
        writeManifest(manifest, runId, config, scenarioStart);

        ScenarioPlan plan = PlanGenerator.generate(config);
        List<TerminalRun> runs = new ArrayList<>();
        try (Recorder recorder = new Recorder(runId, sendLog, ackLog)) {
            List<Thread> threads = new ArrayList<>();
            for (String terminal : plan.terminals()) {
                TerminalRun run = new TerminalRun(config, terminal, recorder, plan, scenarioStart);
                runs.add(run);
                threads.add(Thread.ofPlatform().name("scenario-" + terminal).unstarted(run));
            }
            threads.forEach(Thread::start);
            for (Thread thread : threads) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        List<TerminalResult> results = runs.stream().map(TerminalRun::result).toList();
        boolean success = results.stream().allMatch(result -> "OK".equals(result.status()));
        writeSummary(summaryFile, runId, results, success);
        return new RunSummary(runId, sendLog, ackLog, summaryFile, manifest, success, results);
    }

    private static void writeManifest(Path path, String runId, ScenarioConfig config,
                                      LocalDateTime scenarioStart) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write("run_id=" + runId + "\n");
            writer.write("started_at=" + LocalDateTime.now() + "\n");
            writer.write("seed=" + config.seed() + "\n");
            writer.write("vehicles=" + String.join(",", config.vehicles()) + "\n");
            writer.write("host=" + config.host() + "\n");
            writer.write("port=" + config.port() + "\n");
            writer.write("interval_ms=" + config.intervalMs() + "\n");
            writer.write("vehicle_start_stagger_ms=" + config.vehicleStartStaggerMs() + "\n");
            writer.write("ack_timeout_ms=" + config.ackTimeoutMs() + "\n");
            writer.write("ack_max_retries=" + config.ackMaxRetries() + "\n");
            writer.write("ack_loss_drop_count=" + config.ackLossDropCount() + "\n");
            writer.write("command_window_ms=" + config.commandWindowMs() + "\n");
            writer.write("command_base_interval_ms=" + config.commandBaseIntervalMs() + "\n");
            writer.write("offline_gap_ms=" + config.offlineGapMs() + "\n");
            writer.write("backfill_seconds=" + config.backfillSeconds() + "\n");
            writer.write("duration_ms=" + config.durationMs() + "\n");
            writer.write("scenario_start=" + scenarioStart + "\n");
            writer.write("route_crs=" + config.route().crs() + "\n");
            writer.write("route_start_lng=" + config.route().startLng() + "\n");
            writer.write("route_start_lat=" + config.route().startLat() + "\n");
            writer.write("route_step_lng=" + config.route().stepLng() + "\n");
            writer.write("geofence_step_lng=" + config.geofenceStepLng() + "\n");
            writer.write("scenes=" + config.enabledScenes() + "\n");
            writer.write("java_version=" + System.getProperty("java.version") + "\n");
            writer.write("os_name=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\n");
        }
    }

    private static void writeSummary(Path path, String runId, List<TerminalResult> results, boolean success)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write("terminal\tstatus\tfailure_reason\tsent_count\tack_count\tremaining_entries\n");
            for (TerminalResult result : results) {
                writer.write(String.join("\t", result.terminalNo(), result.status(),
                        sanitize(result.failureReason()), Long.toString(result.sentCount()),
                        Long.toString(result.ackCount()), Integer.toString(result.remainingEntries())) + "\n");
            }
            writer.write(String.join("\t", "RUN", success ? "SUCCESS" : "FAILURE",
                    sanitize("run_id=" + runId), "", "", "") + "\n");
        }
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static final class TerminalRun implements Runnable {

        private final ScenarioConfig config;
        private final String terminal;
        private final Recorder recorder;
        private final LocalDateTime scenarioStart;
        private final List<PlanEntry> entries;
        private final Serial serial = new Serial();
        private Socket socket;
        private FrameReader reader;
        private long timelineStartNanos;
        private long sentCount;
        private long ackCount;
        private int completedEntries;
        private volatile TerminalResult result;

        TerminalRun(ScenarioConfig config, String terminal, Recorder recorder, ScenarioPlan plan,
                    LocalDateTime scenarioStart) {
            this.config = config;
            this.terminal = terminal;
            this.recorder = recorder;
            this.scenarioStart = scenarioStart;
            this.entries = plan.entries().stream()
                    .filter(entry -> entry.terminalNo().equals(terminal)).toList();
            this.result = new TerminalResult(terminal, "ABORTED", "未执行", 0, 0, entries.size());
        }

        TerminalResult result() {
            return result;
        }

        @Override
        public void run() {
            timelineStartNanos = System.nanoTime();
            String status = "OK";
            String failureReason = "";
            String firstFailure = "";
            int index = 0;
            try {
                while (index < entries.size()) {
                    PlanEntry entry = entries.get(index);
                    switch (entry.kind()) {
                        case PlanEntry.CONNECT -> {
                            waitUntil(entry.offsetMs());
                            closeQuietly();
                            establish(entry);
                        }
                        case PlanEntry.DISCONNECT -> {
                            waitUntil(entry.offsetMs());
                            closeQuietly();
                            recorder.recordControl(terminal, entry.scene(), PlanEntry.DISCONNECT,
                                    entry.offsetMs(), "planned disconnect without logout");
                            sentCount++;
                        }
                        case PlanEntry.POSITION -> {
                            if (socket == null) {
                                throw new Aborted("计划上报位置时连接不存在（offset=" + entry.offsetMs() + "ms）");
                            }
                            waitUntil(entry.offsetMs());
                            SendOutcome outcome = sendPlanEntry(entry);
                            if (!outcome.success() && firstFailure.isEmpty()) {
                                firstFailure = outcome.failureReason();
                            }
                        }
                        case PlanEntry.LOGOUT -> {
                            if (socket == null) {
                                throw new Aborted("计划注销时连接不存在（offset=" + entry.offsetMs() + "ms）");
                            }
                            waitUntil(entry.offsetMs());
                            SendOutcome outcome = sendPlanEntry(entry);
                            closeQuietly();
                            if (!outcome.success() && firstFailure.isEmpty()) {
                                firstFailure = outcome.failureReason();
                            }
                        }
                        default -> throw new Aborted("未知计划类型：" + entry.kind());
                    }
                    index++;
                    completedEntries = index;
                }
            } catch (Disconnected e) {
                status = "ABORTED";
                failureReason = "UNEXPECTED_DISCONNECT: " + e.getMessage();
            } catch (Aborted e) {
                status = "ABORTED";
                failureReason = e.getMessage();
            } catch (Exception e) {
                status = "ABORTED";
                failureReason = "RUNNER_ERROR: " + e;
            } finally {
                closeQuietly();
                if ("OK".equals(status) && !firstFailure.isEmpty()) {
                    status = "FAILED";
                    failureReason = firstFailure;
                }
                result = new TerminalResult(terminal, status, failureReason, sentCount, ackCount,
                        entries.size() - completedEntries);
            }
        }

        private void establish(PlanEntry entry) throws Disconnected, Aborted, IOException {
            Socket candidate = new Socket();
            try {
                candidate.connect(new InetSocketAddress(config.host(), config.port()), CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                try {
                    candidate.close();
                } catch (IOException ignored) {
                    // 连接未建立，关闭失败不影响错误记录。
                }
                throw new Aborted("CONNECT_FAILED: " + e.getMessage());
            }
            socket = candidate;
            reader = new FrameReader(candidate);

            SendOutcome register = performSend("REGISTER", entry.scene(), entry.offsetMs(), 0x0100,
                    new TerminalRegister(1, 1, "VMS01", "SIMULATOR", terminal, 2, "模拟终端").encode(),
                    0, 0, 0, 0, false, 0, "");
            if (!register.success()) {
                throw new Aborted("注册失败：" + register.failureReason());
            }
            SendOutcome auth = performSend("AUTH", entry.scene(), entry.offsetMs(), 0x0102,
                    new TerminalAuthentication(register.authCode(), IMEI, VERSION).encode(),
                    0, 0, 0, 0, false, 0, "");
            if (!auth.success()) {
                throw new Aborted("鉴权失败：" + auth.failureReason());
            }
            // 心跳只用于确认会话可用；失败已写入 ACK 记录，不阻断后续计划。
            performSend("HEARTBEAT", entry.scene(), entry.offsetMs(), 0x0002, new byte[0],
                    0, 0, 0, 0, false, 0, "");
        }

        private SendOutcome sendPlanEntry(PlanEntry entry) throws Disconnected, IOException {
            if (entry.messageId() == 0x0704) {
                return performSend(entry.kind(), entry.scene(), entry.offsetMs(), 0x0704,
                        batchBody(entry), entry.lng(), entry.lat(), entry.speed(), entry.direction(),
                        entry.fixValid(), entry.dropAcks(), itemsColumn(entry.items()));
            }
            if (entry.messageId() == 0x0200) {
                return performSend(entry.kind(), entry.scene(), entry.offsetMs(), 0x0200,
                        positionBody(entry.lng(), entry.lat(), entry.speed(), entry.direction(),
                                entry.fixValid(), entry.offsetMs()), entry.lng(), entry.lat(), entry.speed(),
                        entry.direction(), entry.fixValid(), entry.dropAcks(), "");
            }
            if (entry.messageId() == 0x0003) {
                return performSend(entry.kind(), entry.scene(), entry.offsetMs(), 0x0003, new byte[0],
                        0, 0, 0, 0, false, entry.dropAcks(), "");
            }
            throw new IllegalStateException("不支持的计划报文 0x" + Integer.toHexString(entry.messageId()));
        }

        private byte[] positionBody(double lng, double lat, int speed, int direction, boolean fixValid,
                                    long plannedOffsetMs) {
            LocalDateTime time = plannedTime(plannedOffsetMs);
            return new PositionReport(0, fixValid ? 3 : 1, lat, lng, 0, speed, direction, time, List.of())
                    .encode();
        }

        private byte[] batchBody(PlanEntry entry) {
            LocalDateTime time = plannedTime(entry.offsetMs());
            List<PositionReport> reports = new ArrayList<>();
            for (PlanItem item : entry.items()) {
                reports.add(new PositionReport(0, item.fixValid() ? 3 : 1, item.lat(), item.lng(), 0,
                        item.speed(), item.direction(), time.plusSeconds(item.gpsOffsetSeconds()), List.of()));
            }
            return new PositionBatch(PositionBatch.TYPE_NORMAL, reports).encode();
        }

        /** 终端上报时间由计划时间线推导，而不是发送时的墙钟：否则调度抖动会把相邻计划点
         *  压进同一秒，平台按 (terminal_id, gps_time) 幂等处理时随机丢点，运行不可重复。 */
        private LocalDateTime plannedTime(long plannedOffsetMs) {
            return scenarioStart.plus(plannedOffsetMs, ChronoUnit.MILLIS).truncatedTo(ChronoUnit.SECONDS);
        }

        private String itemsColumn(List<PlanItem> items) {
            StringBuilder builder = new StringBuilder();
            for (PlanItem item : items) {
                builder.append(item.gpsOffsetSeconds()).append(',')
                        .append(format(item.lng())).append(',')
                        .append(format(item.lat())).append(',')
                        .append(item.speed()).append(',')
                        .append(item.direction()).append(',')
                        .append(item.fixValid() ? 1 : 0).append(';');
            }
            return builder.toString();
        }

        /**
         * 发送一次并等待应答；对同一 serial 重发，最多 1 + ack-max-retries 次尝试。
         * 收到明确失败应答立即停止重试；连接异常先记录 DISCONNECTED 再终止该终端。
         */
        private SendOutcome performSend(String kind, ScenarioType scene, long plannedOffsetMs, int messageId,
                                        byte[] body, double lng, double lat, int speed, int direction,
                                        boolean fixValid, int dropAcks, String items) throws Disconnected, IOException {
            int frameSerial = serial.next();
            int maxAttempts = 1 + config.ackMaxRetries();
            int remainingDrops = dropAcks;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                long sendId = recorder.recordSend(terminal, scene, kind, messageId, frameSerial, attempt,
                        plannedOffsetMs, lng, lat, speed, direction, fixValid, dropAcks, items);
                sentCount++;
                long sentNanos = System.nanoTime();
                try {
                    writeFrame(frame(messageId, frameSerial, body));
                    long deadline = sentNanos + TimeUnit.MILLISECONDS.toNanos(config.ackTimeoutMs());
                    boolean droppedThisAttempt = false;
                    while (true) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) {
                            break;
                        }
                        Frame incoming = reader.next((int) Math.max(1,
                                Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), POLL_MS)));
                        if (incoming == null) {
                            continue;
                        }
                        if (handlePlatformCommand(incoming)) {
                            continue;
                        }
                        ReceivedAck ack = matchAck(incoming, frameSerial, messageId);
                        if (ack == null) {
                            continue;
                        }
                        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sentNanos);
                        boolean registerWithoutCode = messageId == 0x0100
                                && (ack.authCode() == null || ack.authCode().isBlank());
                        if (ack.result() != 0 || registerWithoutCode) {
                            String reason = registerWithoutCode ? "注册应答缺少鉴权码"
                                    : "服务端拒绝 result=" + ack.result();
                            recorder.recordAck(sendId, terminal, messageId, frameSerial, attempt,
                                    ack.ackMessageId(), ack.result(), "ACK_REJECTED", latencyMs, reason);
                            ackCount++;
                            return new SendOutcome(false, kind + " serial=" + frameSerial + " " + reason, null);
                        }
                        if (remainingDrops > 0) {
                            remainingDrops--;
                            droppedThisAttempt = true;
                            recorder.recordAck(sendId, terminal, messageId, frameSerial, attempt,
                                    ack.ackMessageId(), ack.result(), "ACK_DROPPED", latencyMs,
                                    "模拟 ACK 丢失（剩余 " + remainingDrops + " 次）");
                            ackCount++;
                            continue;
                        }
                        recorder.recordAck(sendId, terminal, messageId, frameSerial, attempt,
                                ack.ackMessageId(), ack.result(), "ACK_SUCCESS", latencyMs, "");
                        ackCount++;
                        return new SendOutcome(true, "", ack.authCode());
                    }
                    if (!droppedThisAttempt || attempt == maxAttempts) {
                        String reason = attempt == maxAttempts
                                ? "ACK 超时且已达最大重试次数（ack-max-retries=" + config.ackMaxRetries() + "）"
                                : "ACK 超时（ack-timeout-ms=" + config.ackTimeoutMs() + "），准备第 "
                                        + (attempt + 1) + " 次尝试";
                        recorder.recordAck(sendId, terminal, messageId, frameSerial, attempt, 0, -1,
                                "NO_ACK", config.ackTimeoutMs(), reason);
                        ackCount++;
                    }
                } catch (Disconnected e) {
                    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sentNanos);
                    recorder.recordAck(sendId, terminal, messageId, frameSerial, attempt, 0, -1,
                            "DISCONNECTED", latencyMs, e.getMessage());
                    ackCount++;
                    throw e;
                }
            }
            return new SendOutcome(false, kind + " serial=" + frameSerial + " ACK 超时：已尝试 "
                    + maxAttempts + " 次", null);
        }
        private ReceivedAck matchAck(Frame incoming, int expectedSerial, int sentMessageId) {
            if (sentMessageId == 0x0100) {
                if (incoming.header().messageId() != 0x8100) {
                    return null;
                }
                TerminalRegisterResponse response = TerminalRegisterResponse.parse(incoming.body());
                return response.responseSerial() == expectedSerial
                        ? new ReceivedAck(0x8100, response.result(), response.authCode()) : null;
            }
            if (incoming.header().messageId() != 0x8001) {
                return null;
            }
            PlatformGeneralResponse response = PlatformGeneralResponse.parse(incoming.body());
            if (response.responseSerial() != expectedSerial || response.responseId() != sentMessageId) {
                return null;
            }
            return new ReceivedAck(0x8001, response.result(), null);
        }

        private boolean handlePlatformCommand(Frame incoming) throws Disconnected, IOException {
            int messageId = incoming.header().messageId();
            if (messageId != 0x8300 && messageId != 0x8202) {
                return false;
            }
            String detail;
            if (messageId == 0x8300) {
                TextIssue text = TextIssue.parse(incoming.body());
                detail = "textType=" + text.textType() + " flag=" + text.flag() + " text=" + text.text();
            } else {
                TemporaryTracking tracking = TemporaryTracking.parse(incoming.body());
                detail = "intervalSeconds=" + tracking.intervalSeconds() + " validSeconds="
                        + tracking.validSeconds() + " stop=" + tracking.stop();
            }
            int responseSerial = serial.next();
            recorder.recordCommandResponse(terminal, incoming.header().serialNumber(), messageId,
                    responseSerial, detail);
            ackCount++;
            writeFrame(frame(0x0001, responseSerial,
                    new TerminalGeneralResponse(incoming.header().serialNumber(), messageId, 0).encode()));
            return true;
        }

        private void waitUntil(long offsetMs) throws Disconnected, IOException {
            long deadline = timelineStartNanos + TimeUnit.MILLISECONDS.toNanos(offsetMs);
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                if (socket == null) {
                    sleepNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
                    continue;
                }
                int waitMs = (int) Math.max(1,
                        Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), POLL_MS));
                Frame incoming = reader.next(waitMs);
                if (incoming != null) {
                    handlePlatformCommand(incoming);
                }
            }
        }

        private void sleepNanos(long nanos) {
            try {
                Thread.sleep(Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private Frame frame(int messageId, int frameSerial, byte[] body) {
            return new Frame(new MessageHeader(messageId, BodyProperties.plain(body.length), 1,
                    terminal, frameSerial), body);
        }

        private void writeFrame(Frame outgoing) throws Disconnected {
            try {
                OutputStream output = socket.getOutputStream();
                output.write(FrameCodec.encode(outgoing));
                output.flush();
            } catch (IOException e) {
                throw new Disconnected("写入失败：" + e.getMessage(), e);
            }
        }

        private void closeQuietly() {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响已记录的结果。
                }
                socket = null;
                reader = null;
            }
        }

        private String format(double value) {
            return String.format(Locale.ROOT, "%.6f", value);
        }

        private final class FrameReader {

            private final Socket source;
            private final byte[] buffer = new byte[8192];
            private int length;

            FrameReader(Socket source) {
                this.source = source;
            }

            Frame next(int timeoutMs) throws Disconnected {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                while (true) {
                    int end = firstFrameEnd();
                    if (end > 0) {
                        byte[] raw = Arrays.copyOf(buffer, end);
                        System.arraycopy(buffer, end, buffer, 0, length - end);
                        length -= end;
                        try {
                            return FrameCodec.decode(raw);
                        } catch (RuntimeException e) {
                            throw new Disconnected("协议解析失败：" + e.getMessage(), e);
                        }
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        return null;
                    }
                    try {
                        source.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                        InputStream input = source.getInputStream();
                        int read = input.read(buffer, length, buffer.length - length);
                        if (read < 0) {
                            throw new Disconnected("服务端关闭连接", null);
                        }
                        length += read;
                    } catch (SocketTimeoutException e) {
                        return null;
                    } catch (IOException e) {
                        throw new Disconnected("读取失败：" + e.getMessage(), e);
                    }
                }
            }

            private int firstFrameEnd() {
                int start = -1;
                for (int i = 0; i < length; i++) {
                    if ((buffer[i] & 0xFF) == 0x7E) {
                        if (start < 0) {
                            start = i;
                        } else {
                            return i + 1;
                        }
                    }
                }
                return -1;
            }
        }
    }

    /** 终端侧消息流水号，同一终端重连后继续递增。 */
    private static final class Serial {
        private int value = 1;

        int next() {
            int current = value;
            value = (value + 1) & 0xFFFF;
            return current;
        }
    }

    private record ReceivedAck(int ackMessageId, int result, String authCode) {
    }

    private record SendOutcome(boolean success, String failureReason, String authCode) {
    }

    private static final class Disconnected extends Exception {
        Disconnected(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Aborted extends Exception {
        Aborted(String message) {
            super(message);
        }
    }

    /**
     * 两类记录：发送记录记录每次尝试；ACK/应答记录记录应答、超时、丢弃、拒绝与平台指令回应。
     * 两者以 send_id 对齐；平台指令回应没有对应发送，send_id 记为 "-"。
     */
    private static final class Recorder implements AutoCloseable {

        private final String runId;
        private final BufferedWriter sendWriter;
        private final BufferedWriter ackWriter;
        private long sendIds;
        private long ackIds;

        Recorder(String runId, Path sendPath, Path ackPath) throws IOException {
            this.runId = runId;
            this.sendWriter = Files.newBufferedWriter(sendPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            this.ackWriter = Files.newBufferedWriter(ackPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            sendWriter.write("send_id\trun_id\tterminal\tscene\tkind\tmessage_id\tserial\tattempt"
                    + "\tplanned_offset_ms\tsent_at\tlng\tlat\tspeed\tdirection\tfix_valid\tdrop_acks\titems\n");
            ackWriter.write("ack_id\trun_id\tsend_id\tterminal\tmessage_id\tserial\tattempt"
                    + "\tack_message_id\tresult\tstatus\tlatency_ms\treceived_at\treason\n");
            sendWriter.flush();
            ackWriter.flush();
        }

        synchronized long recordSend(String terminal, ScenarioType scene, String kind, int messageId,
                                     int serial, int attempt, long plannedOffsetMs, double lng, double lat,
                                     int speed, int direction, boolean fixValid, int dropAcks, String items)
                throws IOException {
            long sendId = ++sendIds;
            String row = String.join("\t", Long.toString(sendId), runId, terminal, scene.key(), kind,
                    String.format(Locale.ROOT, "%04X", messageId), Integer.toString(serial),
                    Integer.toString(attempt), Long.toString(plannedOffsetMs), LocalDateTime.now().toString(),
                    format(lng), format(lat), Integer.toString(speed), Integer.toString(direction),
                    fixValid ? "1" : "0", Integer.toString(dropAcks), items);
            sendWriter.write(row + "\n");
            sendWriter.flush();
            return sendId;
        }

        synchronized void recordControl(String terminal, ScenarioType scene, String kind, long offsetMs,
                                        String detail) throws IOException {
            long sendId = ++sendIds;
            String row = String.join("\t", Long.toString(sendId), runId, terminal, scene.key(), kind,
                    "-", "-", "1", Long.toString(offsetMs), LocalDateTime.now().toString(),
                    "", "", "", "", "", "0", detail);
            sendWriter.write(row + "\n");
            sendWriter.flush();
        }

        synchronized void recordAck(long sendId, String terminal, int messageId, int serial, int attempt,
                                    int ackMessageId, int result, String status, long latencyMs, String reason)
                throws IOException {
            long ackId = ++ackIds;
            String row = String.join("\t", Long.toString(ackId), runId, Long.toString(sendId), terminal,
                    String.format(Locale.ROOT, "%04X", messageId), Integer.toString(serial),
                    Integer.toString(attempt), String.format(Locale.ROOT, "%04X", ackMessageId),
                    Integer.toString(result), status, Long.toString(latencyMs),
                    LocalDateTime.now().toString(), sanitize(reason));
            ackWriter.write(row + "\n");
            ackWriter.flush();
        }

        synchronized void recordCommandResponse(String terminal, int commandSerial, int commandMessageId,
                                                int responseSerial, String detail) throws IOException {
            long ackId = ++ackIds;
            String row = String.join("\t", Long.toString(ackId), runId, "-", terminal, "0001",
                    Integer.toString(commandSerial), "1",
                    String.format(Locale.ROOT, "%04X", commandMessageId), "0", "COMMAND_RESPONSE",
                    "0", LocalDateTime.now().toString(),
                    sanitize("responseSerial=" + responseSerial + " " + detail));
            ackWriter.write(row + "\n");
            ackWriter.flush();
        }

        private static String format(double value) {
            return String.format(Locale.ROOT, "%.6f", value);
        }

        @Override
        public synchronized void close() throws IOException {
            try {
                sendWriter.close();
            } finally {
                ackWriter.close();
            }
        }
    }
}




