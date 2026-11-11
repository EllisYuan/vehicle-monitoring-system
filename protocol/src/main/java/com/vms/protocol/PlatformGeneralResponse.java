package com.vms.protocol;

/**
 * 平台通用应答（JT/T 808-2019 表 5，消息 ID 0x8001）。字段与终端通用应答一致。
 */
public record PlatformGeneralResponse(int responseSerial, int responseId, int result) {

    public static PlatformGeneralResponse parse(byte[] body) {
        BodyReader reader = new BodyReader(body);
        PlatformGeneralResponse value = new PlatformGeneralResponse(
                reader.readUnsignedShort(), reader.readUnsignedShort(), reader.readUnsignedByte());
        reader.requireExhausted();
        return value;
    }

    public byte[] encode() {
        byte[] out = new byte[5];
        Bytes.putUnsignedShort(out, 0, responseSerial);
        Bytes.putUnsignedShort(out, 2, responseId);
        out[4] = (byte) result;
        return out;
    }
}
