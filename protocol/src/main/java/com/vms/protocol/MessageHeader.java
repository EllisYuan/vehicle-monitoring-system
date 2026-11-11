package com.vms.protocol;

import java.util.Arrays;

/**
 * 2019 版消息头（JT/T 808-2019 表 2）：消息 ID、消息体属性、协议版本号、
 * 终端手机号 BCD[10]、消息流水号，分包时再接 4 字节消息包封装项。
 * <p>
 * 本子集不支持分包，因此 {@link #packageInfo()} 恒为空；解析到分包标志时由
 * {@link FrameCodec} 拒绝，而不是静默忽略。
 */
public record MessageHeader(
        int messageId,
        BodyProperties properties,
        int protocolVersion,
        String terminalPhone,
        int serialNumber) {

    /** 不含封装项的消息头长度：2 + 2 + 1 + 10 + 2。 */
    public static final int FIXED_LENGTH = 17;

    public MessageHeader {
        if (terminalPhone == null || terminalPhone.length() != 20) {
            throw new IllegalArgumentException("终端手机号必须是 20 位十进制 BCD，实际：" + terminalPhone);
        }
        if (protocolVersion != 1) {
            throw new IllegalArgumentException("本子集只支持 JT/T 808-2019 协议版本 1，实际：" + protocolVersion);
        }
        if (serialNumber < 0 || serialNumber > 0xFFFF) {
            throw new IllegalArgumentException("消息流水号超出 WORD 范围：" + serialNumber);
        }
    }

    public MessageType type() {
        return MessageType.of(messageId);
    }

    public int bodyLength() {
        return properties.rawLength();
    }

    public int length() {
        return FIXED_LENGTH + (properties.packaged() ? 4 : 0);
    }

    byte[] encode() {
        byte[] out = new byte[length()];
        Bytes.putUnsignedShort(out, 0, messageId);
        Bytes.putUnsignedShort(out, 2, properties.toInt());
        out[4] = (byte) protocolVersion;
        System.arraycopy(Bcd.encode(terminalPhone), 0, out, 5, 10);
        Bytes.putUnsignedShort(out, 15, serialNumber);
        return out;
    }

    static MessageHeader decode(byte[] data, int offset) {
        if (data.length - offset < FIXED_LENGTH) {
            throw new MalformedFrameException("消息头不足 " + FIXED_LENGTH + " 字节");
        }
        int messageId = Bytes.unsignedShort(data, offset);
        BodyProperties properties = BodyProperties.of(Bytes.unsignedShort(data, offset + 2));
        int version = data[offset + 4] & 0xFF;
        if (version != 1) {
            throw new UnsupportedMessageException("VERSION_2011", "本子集只支持带版本标识的 2019 消息头");
        }
        String phone = Bcd.decode(Arrays.copyOfRange(data, offset + 5, offset + 15));
        int serial = Bytes.unsignedShort(data, offset + 15);
        return new MessageHeader(messageId, properties, version, phone, serial);
    }
}
