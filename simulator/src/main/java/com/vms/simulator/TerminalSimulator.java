package com.vms.simulator;

import com.vms.protocol.BodyProperties;
import com.vms.protocol.Frame;
import com.vms.protocol.FrameCodec;
import com.vms.protocol.MessageHeader;
import com.vms.protocol.PlatformGeneralResponse;
import com.vms.protocol.PositionReport;
import com.vms.protocol.TerminalAuthentication;
import com.vms.protocol.TerminalRegister;
import com.vms.protocol.TerminalRegisterResponse;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 最小真实 TCP JT/T 808 模拟终端；输出标明模拟数据，不记录鉴权码。 */
public final class TerminalSimulator {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int RESPONSE_TIMEOUT_MS = 5000;
    private static final long HEARTBEAT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final double LONGITUDE_STEP = 0.0001;
    private static final ZoneId POSITION_ZONE = ZoneId.of("Asia/Shanghai");

    private TerminalSimulator() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        System.out.println("simulation: JT/T 808 terminal, simulated WGS84 positions");
        try (Socket socket = new Socket()) {
            try {
                socket.connect(new InetSocketAddress(options.host(), options.port()), CONNECT_TIMEOUT_MS);
            } catch (SocketTimeoutException e) {
                throw new SocketTimeoutException("TCP connect timeout after " + CONNECT_TIMEOUT_MS + " ms");
            }
            socket.setSoTimeout(RESPONSE_TIMEOUT_MS);
            int serial = 1;
            String terminalNo = options.terminalNo();
            write(socket, frame(0x0100, serial, new TerminalRegister(1, 1, "VMS01", "SIMULATOR",
                    terminalNo, 2, "模拟终端").encode(), terminalNo));
            Frame registerFrame = read(socket, "register");
            requireHeader("register", registerFrame, 0x8100, terminalNo);
            TerminalRegisterResponse response = TerminalRegisterResponse.parse(registerFrame.body());
            if (response.responseSerial() != serial) {
                throw new IllegalStateException("register ACK serial mismatch: expected=" + serial
                        + ", actual=" + response.responseSerial());
            }
            if (response.result() != TerminalRegisterResponse.RESULT_SUCCESS
                    || response.authCode() == null || response.authCode().isBlank()) {
                throw new IllegalStateException("register rejected, result=" + response.result());
            }
            System.out.println("register: accepted");
            serial = nextSerial(serial);
            exchange(socket, terminalNo, "authentication", 0x0102, serial,
                    new TerminalAuthentication(response.authCode(), options.imei(), options.version()).encode());
            System.out.println("authentication: accepted");
            serial = nextSerial(serial);
            exchange(socket, terminalNo, "heartbeat", 0x0002, serial, new byte[0]);
            System.out.println("heartbeat: accepted");
            serial = nextSerial(serial);
            for (int i = 0; i < options.positions(); i++) {
                if (i > 0) {
                    serial = waitWithHeartbeats(socket, terminalNo, serial, options.intervalMs());
                }
                PositionReport position = new PositionReport(0, 3, options.latitude(),
                        options.longitude() + i * LONGITUDE_STEP, 0, options.speed(), options.direction(),
                        LocalDateTime.now(POSITION_ZONE).truncatedTo(ChronoUnit.SECONDS), List.of());
                exchange(socket, terminalNo, "position", 0x0200, serial, position.encode());
                System.out.println("position: accepted serial=" + serial + " simulation=true");
                serial = nextSerial(serial);
            }
            serial = waitWithHeartbeats(socket, terminalNo, serial, options.lingerMs());
            exchange(socket, terminalNo, "logout", 0x0003, serial, new byte[0]);
            System.out.println("logout: accepted");
        }
    }

    private static int waitWithHeartbeats(Socket socket, String terminalNo, int serial, int durationMs)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs);
        long nextHeartbeat = System.nanoTime() + HEARTBEAT_INTERVAL_NANOS;
        while (true) {
            long now = System.nanoTime();
            long remaining = deadline - now;
            if (remaining <= 0) {
                return serial;
            }
            if (now >= nextHeartbeat) {
                exchange(socket, terminalNo, "heartbeat", 0x0002, serial, new byte[0]);
                System.out.println("heartbeat: accepted");
                serial = nextSerial(serial);
                nextHeartbeat = System.nanoTime() + HEARTBEAT_INTERVAL_NANOS;
            } else {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, nextHeartbeat - now));
            }
        }
    }

    private static int nextSerial(int serial) {
        return (serial + 1) & 0xFFFF;
    }

    private static Frame frame(int id, int serial, byte[] body, String terminalNo) {
        return new Frame(new MessageHeader(id, BodyProperties.plain(body.length), 1, terminalNo, serial), body);
    }

    private static void exchange(Socket socket, String terminalNo, String name, int id, int serial, byte[] body)
            throws Exception {
        write(socket, frame(id, serial, body, terminalNo));
        Frame response = read(socket, name);
        requireHeader(name, response, 0x8001, terminalNo);
        requireSuccess(name, PlatformGeneralResponse.parse(response.body()), id, serial);
    }

    private static void write(Socket socket, Frame frame) throws Exception {
        socket.getOutputStream().write(FrameCodec.encode(frame));
        socket.getOutputStream().flush();
    }

    private static Frame read(Socket socket, String name) throws Exception {
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[4096];
        int length = 0;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RESPONSE_TIMEOUT_MS);
        while (length < buffer.length) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw responseTimeout(name);
            }
            socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            int read;
            try {
                read = in.read(buffer, length, buffer.length - length);
            } catch (SocketTimeoutException e) {
                throw responseTimeout(name);
            }
            if (read < 0) {
                throw new IllegalStateException(name + ": TCP listener closed before response");
            }
            length += read;
            var frames = FrameCodec.splitFrames(Arrays.copyOf(buffer, length));
            if (!frames.isEmpty()) {
                return FrameCodec.decode(frames.getFirst());
            }
        }
        throw new IllegalStateException(name + ": TCP response exceeds " + buffer.length + " bytes");
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

    private record Options(String host, int port, String terminalNo, String imei, String version,
            int positions, int intervalMs, int lingerMs, double longitude, double latitude, int speed, int direction) {

        private static Options parse(String[] args) {
            Set<String> names = Set.of("--host", "--port", "--terminal", "--imei", "--version", "--positions",
                    "--interval-ms", "--linger-ms", "--longitude", "--latitude", "--speed", "--direction");
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
            // status=3 固定为 ACC+有效定位，南纬/西经状态位为 0，只接受东北半球坐标。
            double longitude = decimal(values, "--longitude", 116.397, 0, 180);
            double latitude = decimal(values, "--latitude", 39.908, 0, 90);
            if (longitude + Math.max(0, positions - 1) * LONGITUDE_STEP > 180) {
                throw new IllegalArgumentException("--positions moves longitude beyond 180 degrees");
            }
            int speed = (int) Math.round(decimal(values, "--speed", 60, 0, 6553.5) * 10);
            return new Options(host, integer(values, "--port", 9000, 1, 65535), terminalNo, imei, version,
                    positions, intervalMs, lingerMs, longitude, latitude, speed,
                    integer(values, "--direction", 90, 0, 359));
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
