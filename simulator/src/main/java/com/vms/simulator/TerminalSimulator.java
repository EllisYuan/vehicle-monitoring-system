package com.vms.simulator;

import com.vms.protocol.BodyProperties;
import com.vms.protocol.Frame;
import com.vms.protocol.FrameCodec;
import com.vms.protocol.MessageHeader;
import com.vms.protocol.PlatformGeneralResponse;
import com.vms.protocol.TerminalAuthentication;
import com.vms.protocol.TerminalGeneralResponse;
import com.vms.protocol.TerminalRegister;
import com.vms.protocol.TerminalRegisterResponse;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

/** 最小真实 TCP JT/T 808 模拟终端；输出只记录消息类型和结果，不记录鉴权码。 */
public final class TerminalSimulator {

    private TerminalSimulator() {
    }

    public static void main(String[] args) throws Exception {
        String host = option(args, "--host", "127.0.0.1");
        int port = Integer.parseInt(option(args, "--port", "9000"));
        String terminalNo = option(args, "--terminal", "01380013800000000001");
        String imei = option(args, "--imei", "012345678901234");
        String version = option(args, "--version", "00000000000000000001");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            int serial = 1;
            write(socket, frame(0x0100, serial++, new TerminalRegister(1, 1, "VMS01", "SIMULATOR",
                    terminalNo, 2, "模拟终端").encode(), terminalNo));
            Frame registerFrame = read(socket);
            TerminalRegisterResponse response = TerminalRegisterResponse.parse(registerFrame.body());
            if (response.result() != TerminalRegisterResponse.RESULT_SUCCESS || response.authCode() == null) {
                throw new IllegalStateException("终端注册被拒绝，result=" + response.result());
            }
            System.out.println("register: accepted");
            write(socket, frame(0x0102, serial++, new TerminalAuthentication(response.authCode(), imei, version).encode(), terminalNo));
            PlatformGeneralResponse auth = PlatformGeneralResponse.parse(read(socket).body());
            requireSuccess("authentication", auth);
            System.out.println("authentication: accepted");
            write(socket, frame(0x0002, serial++, new byte[0], terminalNo));
            requireSuccess("heartbeat", PlatformGeneralResponse.parse(read(socket).body()));
            System.out.println("heartbeat: accepted");
            write(socket, frame(0x0003, serial, new byte[0], terminalNo));
            requireSuccess("logout", PlatformGeneralResponse.parse(read(socket).body()));
            System.out.println("logout: accepted");
        }
    }

    private static Frame frame(int id, int serial, byte[] body, String terminalNo) {
        return new Frame(new MessageHeader(id, BodyProperties.plain(body.length), 1, terminalNo, serial), body);
    }

    private static void write(Socket socket, Frame frame) throws Exception {
        socket.getOutputStream().write(FrameCodec.encode(frame));
        socket.getOutputStream().flush();
    }

    private static Frame read(Socket socket) throws Exception {
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

    private static void requireSuccess(String name, PlatformGeneralResponse response) {
        if (response.result() != 0) {
            throw new IllegalStateException(name + " rejected, result=" + response.result());
        }
    }

    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
