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
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 最小真实 TCP JT/T 808 模拟终端；输出标明模拟数据，不记录鉴权码。
 * 复用共享 codec 接收平台指令，真实回 0x0001 通用应答，并按 0x8202 调整位置上报节奏。
 * 支持 0x0200 单点与 0x0704 批量上报、断线重连及重连后的旧时刻补传。
 */
public final class TerminalSimulator {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int RESPONSE_TIMEOUT_MS = 5000;
    private static final int COMMAND_POLL_MS = 200;
    private static final long HEARTBEAT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final double LONGITUDE_STEP = 0.0001;
    private static final ZoneId POSITION_ZONE = ZoneId.of("Asia/Shanghai");

    private TerminalSimulator() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        System.out.println("simulation: JT/T 808 terminal, simulated WGS84 positions");
        Session session = connect(options);
        try {
            TrackingControl tracking = new TrackingControl(options.intervalMs());
            if (options.mixedBatch()) {
                sendMixedBatch(session, options);
            } else {
                reportPositions(session, options, tracking, options.positions(), options.longitude(), false);
                if (options.reconnect()) {
                    session.close();
                    System.out.println("reconnect: disconnected");
                    session = connect(options);
                    System.out.println("reconnect: accepted");
                }
                reportPositions(session, options, tracking, options.backfill(),
                        options.longitude() + options.positions() * LONGITUDE_STEP, true);
            }
            waitWithHeartbeats(session, options.lingerMs(), tracking);
            exchange(session, "logout", 0x0003, new byte[0]);
            System.out.println("logout: accepted");
        } finally {
            session.close();
        }
    }

    /** 0x0704 混合包：一个新点、一个完全重复点、一个同秒不同内容的冲突点。 */
    private static void sendMixedBatch(Session session, Options options) throws Exception {
        LocalDateTime time = LocalDateTime.now(POSITION_ZONE).truncatedTo(ChronoUnit.SECONDS);
        PositionReport first = report(options, options.longitude(), time);
        PositionReport conflicting = report(options, options.longitude() + LONGITUDE_STEP * 2, time);
        List<PositionReport> items = List.of(first, first, conflicting);
        exchange(session, "batch", 0x0704, new PositionBatch(PositionBatch.TYPE_NORMAL, items).encode());
        System.out.println("batch: accepted items=" + items.size() + " mixed=true");
    }

    /**
     * 按 --batch-size 打包上报；size=1 使用 0x0200，其余使用 0x0704。
     * backfill=true 时各点 gps_time 回退 --backfill-seconds，用于重连后补传。
     */
    private static void reportPositions(Session session, Options options, TrackingControl tracking,
                                        int count, double startLongitude, boolean backfill) throws Exception {
        int batchSize = Math.max(1, options.batchSize());
        int sent = 0;
        while (sent < count) {
            if (sent > 0) {
                waitWithHeartbeats(session, backfill ? options.backfillIntervalMs()
                        : tracking.currentIntervalMs(), tracking);
            }
            int size = Math.min(batchSize, count - sent);
            List<PositionReport> items = new ArrayList<>(size);
            for (int offset = 0; offset < size; offset++) {
                int index = sent + offset;
                LocalDateTime time = backfill
                        ? LocalDateTime.now(POSITION_ZONE).truncatedTo(ChronoUnit.SECONDS)
                                .minusSeconds(options.backfillSeconds())
                                .plusSeconds((long) index * options.backfillIntervalMs() / 1000)
                        : LocalDateTime.now(POSITION_ZONE).truncatedTo(ChronoUnit.SECONDS);
                items.add(report(options, startLongitude + index * LONGITUDE_STEP, time));
            }
            if (items.size() == 1) {
                exchange(session, "position", 0x0200, items.getFirst().encode());
                System.out.println("position: accepted simulation=true trackingIntervalSeconds="
                        + tracking.activeIntervalSeconds());
            } else {
                exchange(session, "batch", 0x0704, new PositionBatch(PositionBatch.TYPE_NORMAL, items).encode());
                System.out.println("batch: accepted items=" + items.size());
            }
            sent += size;
        }
        if (backfill && count > 0) {
            System.out.println("backfill: accepted items=" + count + " gpsSecondsAgo=" + options.backfillSeconds());
        }
    }

    private static PositionReport report(Options options, double longitude, LocalDateTime time) {
        return new PositionReport(0, options.fixValid() ? 3 : 1, options.latitude(), longitude, 0,
                options.speed(), options.direction(), time, List.of());
    }

    private static Session connect(Options options) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(options.host(), options.port()), CONNECT_TIMEOUT_MS);
        } catch (SocketTimeoutException e) {
            socket.close();
            throw new SocketTimeoutException("TCP connect timeout after " + CONNECT_TIMEOUT_MS + " ms");
        }
        Session session;
        try {
            FrameReader reader = new FrameReader(socket);
            Serial serial = new Serial();
            String terminalNo = options.terminalNo();
            write(socket, frame(0x0100, serial.next(), new TerminalRegister(1, 1, "VMS01", "SIMULATOR",
                    terminalNo, 2, "模拟终端").encode(), terminalNo));
            Frame registerFrame = await(socket, reader, serial, terminalNo, "register", 0x8100, 1);
            TerminalRegisterResponse response = TerminalRegisterResponse.parse(registerFrame.body());
            if (response.result() != TerminalRegisterResponse.RESULT_SUCCESS
                    || response.authCode() == null || response.authCode().isBlank()) {
                throw new IllegalStateException("register rejected, result=" + response.result());
            }
            System.out.println("register: accepted");
            session = new Session(socket, reader, serial, terminalNo);
            exchange(session, "authentication", 0x0102,
                    new TerminalAuthentication(response.authCode(), options.imei(), options.version()).encode());
            System.out.println("authentication: accepted");
            exchange(session, "heartbeat", 0x0002, new byte[0]);
            System.out.println("heartbeat: accepted");
            return session;
        } catch (Exception e) {
            socket.close();
            throw e;
        }
    }

    private static void waitWithHeartbeats(Session session, int durationMs, TrackingControl tracking)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs);
        long nextHeartbeat = System.nanoTime() + HEARTBEAT_INTERVAL_NANOS;
        while (true) {
            long now = System.nanoTime();
            long remaining = deadline - now;
            if (remaining <= 0) {
                return;
            }
            long untilHeartbeat = nextHeartbeat - now;
            int waitMs = (int) Math.max(1, Math.min(TimeUnit.NANOSECONDS.toMillis(remaining),
                    Math.min(TimeUnit.NANOSECONDS.toMillis(Math.max(0, untilHeartbeat)), COMMAND_POLL_MS)));
            Frame frame = session.reader().next(waitMs);
            if (frame != null && handlePlatformCommand(session, frame, tracking)) {
                continue;
            }
            if (System.nanoTime() >= nextHeartbeat) {
                exchange(session, "heartbeat", 0x0002, new byte[0]);
                System.out.println("heartbeat: accepted");
                nextHeartbeat = System.nanoTime() + HEARTBEAT_INTERVAL_NANOS;
            }
        }
    }

    private static void exchange(Session session, String name, int id, byte[] body) throws Exception {
        int frameSerial = session.serial().next();
        write(session.socket(), frame(id, frameSerial, body, session.terminalNo()));
        Frame response = await(session, name, 0x8001, frameSerial);
        requireSuccess(name, PlatformGeneralResponse.parse(response.body()), id, frameSerial);
    }

    /**
     * 等待指定应答；期间收到平台下发的 0x8300/0x8202 时先真实处理并回应，
     * 避免平台指令与上行应答交错时丢失任何一方。
     */
    private static Frame await(Session session, String name, int expectedId, int expectedSerial) throws Exception {
        while (true) {
            Frame response = session.reader().next(RESPONSE_TIMEOUT_MS);
            if (response == null) {
                throw responseTimeout(name);
            }
            if (handlePlatformCommand(session, response, new TrackingControl(1000))) {
                continue;
            }
            requireHeader(name, response, expectedId, session.terminalNo());
            if (response.header().serialNumber() != expectedSerial) {
                throw new IllegalStateException(name + " ACK serial mismatch: expected=" + expectedSerial
                        + ", actual=" + response.header().serialNumber());
            }
            return response;
        }
    }

    private static Frame await(Socket socket, FrameReader reader, Serial serial, String terminalNo,
                               String name, int expectedId, int expectedSerial) throws Exception {
        return await(new Session(socket, reader, serial, terminalNo), name, expectedId, expectedSerial);
    }

    private static boolean handlePlatformCommand(Session session, Frame frame, TrackingControl tracking)
            throws Exception {
        int messageId = frame.header().messageId();
        if (messageId == 0x8300) {
            TextIssue text = TextIssue.parse(frame.body());
            System.out.println("text: received type=" + text.textType() + " flag=" + text.flag()
                    + " content=" + text.text());
            acknowledge(session, frame, 0x8300);
            return true;
        }
        if (messageId == 0x8202) {
            TemporaryTracking command = TemporaryTracking.parse(frame.body());
            tracking.apply(command);
            if (command.stop()) {
                System.out.println("tracking: stopped");
            } else {
                System.out.println("tracking: intervalSeconds=" + command.intervalSeconds()
                        + " validSeconds=" + command.validSeconds());
            }
            acknowledge(session, frame, 0x8202);
            return true;
        }
        return false;
    }

    private static void acknowledge(Session session, Frame command, int commandId) throws Exception {
        TerminalGeneralResponse response = new TerminalGeneralResponse(
                command.header().serialNumber(), commandId, 0);
        write(session.socket(), frame(0x0001, session.serial().next(), response.encode(), session.terminalNo()));
        System.out.println("ack: responseId=0x" + Integer.toHexString(commandId)
                + " commandSerial=" + command.header().serialNumber());
    }

    private static Frame frame(int id, int serial, byte[] body, String terminalNo) {
        return new Frame(new MessageHeader(id, BodyProperties.plain(body.length), 1, terminalNo, serial), body);
    }

    private static void write(Socket socket, Frame frame) throws Exception {
        socket.getOutputStream().write(FrameCodec.encode(frame));
        socket.getOutputStream().flush();
    }

    private static SocketTimeoutException responseTimeout(String name) {
        return new SocketTimeoutException(name + ": TCP response timeout after " + RESPONSE_TIMEOUT_MS + " ms");
    }

    private static void requireHeader(String name, Frame response, int id, String terminalNo) {
        if (response.header().messageId() != id || !terminalNo.equals(response.header().terminalPhone())) {
            throw new IllegalStateException(name + ": unexpected response messageId=0x"
                    + Integer.toHexString(response.header().messageId()) + " or terminal mismatch");
        }
    }

    private static void requireSuccess(String name, PlatformGeneralResponse response, int id, int serial) {
        if (response.responseId() != id || response.responseSerial() != serial) {
            throw new IllegalStateException(name + " ACK mismatch: expected responseId=0x"
                    + Integer.toHexString(id) + ", serial=" + serial + "; actual responseId=0x"
                    + Integer.toHexString(response.responseId()) + ", serial=" + response.responseSerial());
        }
        if (response.result() != 0) {
            throw new IllegalStateException(name + " rejected, result=" + response.result());
        }
    }

    /** 一次 TCP 连接及其帧读取器；重连会新建 Session，不复用旧缓冲。 */
    private record Session(Socket socket, FrameReader reader, Serial serial, String terminalNo)
            implements AutoCloseable {

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** 终端侧消息流水号，平台指令应答也使用同一序列。 */
    private static final class Serial {
        private int value = 1;

        int next() {
            int current = value;
            value = (value + 1) & 0xFFFF;
            return current;
        }
    }

    /** 0x8202 临时跟踪：间隔 0 停止，有效期 0 表示不设到期时间。 */
    private static final class TrackingControl {
        private final int defaultIntervalMs;
        private int intervalSeconds;
        private long validUntilNanos;

        TrackingControl(int defaultIntervalMs) {
            this.defaultIntervalMs = defaultIntervalMs;
        }

        void apply(TemporaryTracking tracking) {
            if (tracking.stop()) {
                intervalSeconds = 0;
                validUntilNanos = 0;
                return;
            }
            intervalSeconds = tracking.intervalSeconds();
            validUntilNanos = tracking.validSeconds() == 0
                    ? Long.MAX_VALUE
                    : System.nanoTime() + TimeUnit.SECONDS.toNanos(tracking.validSeconds());
        }

        int currentIntervalMs() {
            if (intervalSeconds > 0 && System.nanoTime() < validUntilNanos) {
                return intervalSeconds * 1000;
            }
            return defaultIntervalMs;
        }

        int activeIntervalSeconds() {
            return System.nanoTime() < validUntilNanos ? intervalSeconds : 0;
        }
    }

    /**
     * 保留跨读取的未完成字节，避免应答与平台指令相邻到达时丢失帧。
     */
    private static final class FrameReader {
        private final Socket socket;
        private final byte[] buffer = new byte[8192];
        private int length;

        FrameReader(Socket socket) {
            this.socket = socket;
        }

        Frame next(int timeoutMs) throws IOException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (true) {
                int end = firstFrameEnd();
                if (end > 0) {
                    byte[] raw = Arrays.copyOf(buffer, end);
                    System.arraycopy(buffer, end, buffer, 0, length - end);
                    length -= end;
                    return FrameCodec.decode(raw);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return null;
                }
                socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                int read;
                try {
                    read = socket.getInputStream().read(buffer, length, buffer.length - length);
                } catch (SocketTimeoutException e) {
                    return null;
                }
                if (read < 0) {
                    throw new IllegalStateException("TCP listener closed before response");
                }
                length += read;
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

    private record Options(String host, int port, String terminalNo, String imei, String version,
            int positions, int intervalMs, int lingerMs, boolean fixValid, double longitude, double latitude,
            int speed, int direction, String batchMode, int batchSize, int backfill, int backfillSeconds,
            int backfillIntervalMs, boolean reconnect) {

        boolean mixedBatch() {
            return "mixed".equals(batchMode);
        }

        private static Options parse(String[] args) {
            Set<String> names = Set.of("--host", "--port", "--terminal", "--imei", "--version", "--positions",
                    "--interval-ms", "--linger-ms", "--longitude", "--latitude", "--speed", "--direction",
                    "--fix-valid", "--batch", "--batch-size", "--backfill", "--backfill-seconds",
                    "--backfill-interval-ms", "--reconnect");
            Map<String, String> values = new HashMap<>();
            for (int i = 0; i < args.length; i += 2) {
                String name = args[i];
                if (!names.contains(name)) {
                    throw new IllegalArgumentException("Unknown option: " + name);
                }
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for " + name);
                }
                if (values.putIfAbsent(name, args[i + 1]) != null) {
                    throw new IllegalArgumentException("Duplicate option: " + name);
                }
            }
            String host = values.getOrDefault("--host", "127.0.0.1");
            String terminalNo = values.getOrDefault("--terminal", "01380013800000000001");
            String imei = values.getOrDefault("--imei", "012345678901234");
            String version = values.getOrDefault("--version", "00000000000000000001");
            if (host.isBlank() || !terminalNo.matches("[0-9]{20}") || !imei.matches("[0-9]{15}")) {
                throw new IllegalArgumentException("--host must be nonblank; --terminal requires 20 digits; "
                        + "--imei requires 15 digits");
            }
            new TerminalAuthentication("", imei, version).encode();
            int positions = integer(values, "--positions", 0, 0, Integer.MAX_VALUE);
            int intervalMs = integer(values, "--interval-ms", 1000, 1000, Integer.MAX_VALUE);
            int lingerMs = integer(values, "--linger-ms", 0, 0, Integer.MAX_VALUE);
            // --fix-valid=false 模拟结构合法但定位无效（status=ACC，无有效定位）；南纬/西经状态位为 0，只接受东北半球坐标。
            double longitude = decimal(values, "--longitude", 116.397, 0, 180);
            double latitude = decimal(values, "--latitude", 39.908, 0, 90);
            int speed = (int) Math.round(decimal(values, "--speed", 60, 0, 6553.5) * 10);
            String batchMode = values.getOrDefault("--batch", "single");
            if (!"single".equals(batchMode) && !"mixed".equals(batchMode)) {
                throw new IllegalArgumentException("--batch requires single or mixed");
            }
            int batchSize = integer(values, "--batch-size", 1, 1, PositionBatch.MAX_ITEMS);
            int backfill = integer(values, "--backfill", 0, 0, 1000);
            int backfillSeconds = integer(values, "--backfill-seconds", 120, 0, 86400);
            int backfillIntervalMs = integer(values, "--backfill-interval-ms", 1000, 100, 60000);
            if (longitude + Math.max(0, positions + backfill - 1) * LONGITUDE_STEP > 180) {
                throw new IllegalArgumentException("--positions/--backfill moves longitude beyond 180 degrees");
            }
            return new Options(host, integer(values, "--port", 9000, 1, 65535), terminalNo, imei, version,
                    positions, intervalMs, lingerMs, bool(values, "--fix-valid", true), longitude, latitude, speed,
                    integer(values, "--direction", 90, 0, 359), batchMode, batchSize, backfill, backfillSeconds,
                    backfillIntervalMs, bool(values, "--reconnect", false));
        }

        private static int integer(Map<String, String> values, String name, int fallback, int min, int max) {
            try {
                int value = Integer.parseInt(values.getOrDefault(name, Integer.toString(fallback)));
                if (value >= min && value <= max) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // 统一报告参数名和范围，不把数值解析异常直接抛给 CLI 用户。
            }
            throw new IllegalArgumentException(name + " requires an integer in [" + min + ", " + max + "]");
        }

        private static boolean bool(Map<String, String> values, String name, boolean fallback) {
            String value = values.getOrDefault(name, Boolean.toString(fallback));
            if ("true".equals(value)) {
                return true;
            }
            if ("false".equals(value)) {
                return false;
            }
            throw new IllegalArgumentException(name + " requires true or false");
        }

        private static double decimal(Map<String, String> values, String name, double fallback,
                double min, double max) {
            try {
                double value = Double.parseDouble(values.getOrDefault(name, Double.toString(fallback)));
                if (Double.isFinite(value) && value >= min && value <= max) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // NaN、Infinity、越界及非数值输入使用相同的范围错误。
            }
            throw new IllegalArgumentException(name + " requires a finite number in [" + min + ", " + max + "]");
        }
    }
}
