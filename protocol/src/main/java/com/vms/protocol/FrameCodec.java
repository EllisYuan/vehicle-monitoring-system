package com.vms.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 帧编解码（JT/T 808-2019 4.4）。
 * <p>
 * 发送：先封装，再计算校验码，最后转义；接收：先反转义，再验证校验码，最后解析。
 * 转义规则为 0x7d 转 0x7d 0x01、0x7e 转 0x7d 0x02（4.4.2）。
 * 校验码为消息头首字节到消息体末字节逐字节异或（4.4.5）。
 * <p>
 * 本实现只接受不分包、不加密、消息体不超过 1023 字节的报文。
 */
public final class FrameCodec {

    /** 单帧最大长度：标识位 2 + 最长头 21 + 最大体 1023 + 校验 1，转义后最多翻倍。 */
    private static final int MAX_RAW_LENGTH = MessageHeader.FIXED_LENGTH + 4
            + BodyProperties.MAX_BODY_LENGTH + 1;

    private FrameCodec() {
    }

    public static byte[] encode(Frame frame) {
        MessageHeader header = frame.header();
        validateSupportedProperties(header);
        if (header.type() == null) {
            throw new UnsupportedMessageException("UNKNOWN_MESSAGE",
                    "未知消息 ID：0x" + Integer.toHexString(header.messageId()));
        }
        byte[] head = header.encode();
        byte[] body = frame.body();
        if (body.length != header.bodyLength()) {
            throw new IllegalArgumentException(
                    "消息体属性声明 " + header.bodyLength() + " 字节，实际 " + body.length + " 字节");
        }
        byte[] raw = new byte[head.length + body.length + 1];
        System.arraycopy(head, 0, raw, 0, head.length);
        System.arraycopy(body, 0, raw, head.length, body.length);
        raw[raw.length - 1] = checksum(raw, 0, head.length + body.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length + 2);
        out.write(Frame.FLAG);
        for (byte value : raw) {
            if (value == 0x7E) {
                out.write(0x7D);
                out.write(0x02);
            } else if (value == 0x7D) {
                out.write(0x7D);
                out.write(0x01);
            } else {
                out.write(value);
            }
        }
        out.write(Frame.FLAG);
        return out.toByteArray();
    }

    /**
     * 解码单帧。输入必须是恰好一帧（首尾都是 0x7e），否则抛 {@link MalformedFrameException}。
     */
    public static Frame decode(byte[] data) {
        if (data == null || data.length < 2) {
            throw new MalformedFrameException("报文为空或不足一帧");
        }
        if (data[0] != Frame.FLAG || data[data.length - 1] != Frame.FLAG) {
            throw new MalformedFrameException("缺少标识位 0x7e");
        }
        byte[] raw = unescape(data, 1, data.length - 1);
        // 2011 版头没有协议版本号，最短头为 16 字节；可识别时明确拒绝，
        // 不把缺失的版本字节误报为普通截断。
        if (raw.length >= 16 && raw.length < MessageHeader.FIXED_LENGTH + 1) {
            throw new UnsupportedMessageException("VERSION_2011", "本子集只支持带版本标识的 2019 消息头");
        }
        if (raw.length < MessageHeader.FIXED_LENGTH + 1) {
            throw new MalformedFrameException("反转义后长度不足以构成消息头与校验码");
        }
        MessageHeader header = MessageHeader.decode(raw, 0);

        // 长度字段决定消息体边界，校验码必须恰好落在其后，防止截断或多出字节被当成合法帧
        int bodyStart = header.length();
        if (raw.length != bodyStart + header.bodyLength() + 1) {
            throw new MalformedFrameException("消息体长度与消息体属性不一致");
        }
        byte expected = checksum(raw, 0, bodyStart + header.bodyLength());
        byte actual = raw[raw.length - 1];
        if (expected != actual) {
            throw new MalformedFrameException("校验码不匹配");
        }
        validateSupportedProperties(header);
        if (header.type() == null) {
            throw new UnsupportedMessageException("UNKNOWN_MESSAGE",
                    "未知消息 ID：0x" + Integer.toHexString(header.messageId()));
        }
        byte[] body = new byte[header.bodyLength()];
        System.arraycopy(raw, bodyStart, body, 0, body.length);
        return new Frame(header, body);
    }

    /**
     * 从字节流中切出所有完整且可解析的帧。坏帧被跳过，不抛出到调用方，
     * 因为 TCP 流里坏帧之后仍可能有合法报文。
     * <p>
     * 帧内出现的 0x7e 一定已转义为 0x7d 0x02（4.4.2），因此连续的 0x7e 必然成对
     * 标记一帧的首尾。逐对尝试解码，失败的右边界右移一位继续找，避免坏帧吞掉后续好帧。
     */
    public static List<byte[]> splitFrames(byte[] stream) {
        if (stream == null) {
            throw new MalformedFrameException("字节流不能为 null");
        }
        List<Integer> flags = new ArrayList<>();
        for (int i = 0; i < stream.length; i++) {
            if ((stream[i] & 0xFF) == 0x7E) {
                flags.add(i);
            }
        }
        List<byte[]> frames = new ArrayList<>();
        int index = 0;
        while (index + 1 < flags.size()) {
            int from = flags.get(index);
            int to = flags.get(index + 1);
            if (to == from + 1) {
                index++;
                continue;
            }
            byte[] candidate = java.util.Arrays.copyOfRange(stream, from, to + 1);
            try {
                decode(candidate);
                frames.add(candidate);
                // 保留结束标识位作为下一帧的起始候选，兼容共享标识位与双标识位分隔
                index++;
            } catch (MalformedFrameException | UnsupportedMessageException ignored) {
                // 这一对不构成合法帧，右边界后移一位继续找
                index++;
            }
        }
        return frames;
    }

    private static void validateSupportedProperties(MessageHeader header) {
        if (!header.properties().versionFlag()) {
            throw new UnsupportedMessageException("VERSION_2011", "本子集只支持带版本标识的 2019 消息头");
        }
        if (header.properties().packaged()) {
            throw new UnsupportedMessageException("PACKAGED", "本子集不支持分包消息");
        }
        if (header.properties().encrypted()) {
            throw new UnsupportedMessageException("ENCRYPTED", "本子集不支持加密消息");
        }
    }

    private static byte[] unescape(byte[] data, int from, int to) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(to - from);
        for (int i = from; i < to; i++) {
            byte value = data[i];
            if (value != 0x7D) {
                out.write(value);
                continue;
            }
            if (i + 1 >= to) {
                throw new MalformedFrameException("转义符 0x7d 后缺少后续字节");
            }
            byte next = data[++i];
            if (next == 0x01) {
                out.write(0x7D);
            } else if (next == 0x02) {
                out.write(0x7E);
            } else {
                throw new MalformedFrameException("非法转义序列 0x7d 0x" + String.format("%02x", next));
            }
        }
        byte[] raw = out.toByteArray();
        if (raw.length > MAX_RAW_LENGTH) {
            throw new MalformedFrameException("报文超过允许的最大长度");
        }
        return raw;
    }

    private static byte checksum(byte[] data, int from, int to) {
        int value = 0;
        for (int i = from; i < to; i++) {
            value ^= data[i] & 0xFF;
        }
        return (byte) value;
    }
}
