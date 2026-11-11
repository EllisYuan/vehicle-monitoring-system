package com.vms.protocol;

/**
 * 文本信息下发（JT/T 808-2019 表 37、表 38，消息 ID 0x8300）。
 * 标志 BYTE、文本类型 BYTE（1 通知，2 服务）、文本信息 STRING（GBK，最长 1024 字节）。
 */
public record TextIssue(int flag, int textType, String text) {

    /** 表 37 的 1024 字节字段在本子集单包 1023 限制下最多承载 1021 字节。 */
    public static final int MAX_TEXT_BYTES = 1021;

    public static final int TYPE_NOTICE = 1;
    public static final int TYPE_SERVICE = 2;

    public static TextIssue parse(byte[] body) {
        BodyReader reader = new BodyReader(body);
        int flag = reader.readUnsignedByte();
        int type = reader.readUnsignedByte();
        requireType(type);
        if (reader.remaining() > MAX_TEXT_BYTES) {
            throw new MalformedFrameException("文本信息超过本子集单包限制");
        }
        String text = reader.readString(reader.remaining());
        return new TextIssue(flag, type, text);
    }

    public byte[] encode() {
        requireType(textType);
        byte[] bytes = Gbk.encode(text);
        if (bytes.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("文本信息不得超过 " + MAX_TEXT_BYTES + " 字节");
        }
        byte[] out = new byte[2 + bytes.length];
        out[0] = (byte) flag;
        out[1] = (byte) textType;
        System.arraycopy(bytes, 0, out, 2, bytes.length);
        return out;
    }

    private static void requireType(int type) {
        if (type != TYPE_NOTICE && type != TYPE_SERVICE) {
            throw new MalformedFrameException("文本类型不支持：" + type);
        }
    }
}
