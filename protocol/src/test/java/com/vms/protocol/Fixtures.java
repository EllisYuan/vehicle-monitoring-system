package com.vms.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

/**
 * 读取独立 fixture 清单（protocol/src/test/resources/fixtures/jt808-2019.txt）。
 * fixture 由标准条款手工构造，不由本仓库 codec 生成。
 */
final class Fixtures {

    record Case(String name, int messageId, byte[] frame, String source, String expectation) {
        boolean shouldReject() {
            return expectation.startsWith("reject:");
        }

        String rejectionCode() {
            return expectation.substring("reject:".length());
        }
    }

    private Fixtures() {
    }

    static Stream<Case> all() throws IOException {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/jt808-2019.txt")) {
            if (in == null) {
                throw new IllegalStateException("缺少 fixture 清单");
            }
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .toList();
            return lines.stream().map(Fixtures::parse).toList().stream();
        }
    }

    private static Case parse(String line) {
        String[] parts = line.split("\\|");
        if (parts.length != 5) {
            throw new IllegalStateException("fixture 行格式错误：" + line);
        }
        return new Case(parts[0], Integer.decode(parts[1]), hex(parts[2]), parts[3], parts[4]);
    }

    static byte[] hex(String text) {
        String cleaned = text.replace(" ", "");
        if (cleaned.length() % 2 != 0) {
            throw new IllegalArgumentException("十六进制串长度必须为偶数");
        }
        byte[] out = new byte[cleaned.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }
}
