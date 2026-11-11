package com.vms.protocol;

/**
 * 终端通用应答（JT/T 808-2019 表 4，消息 ID 0x0001）。
 * 应答流水号 WORD、应答 ID WORD、结果 BYTE。
 */
public record TerminalGeneralResponse(int responseSerial, int responseId, int result) {

    public static TerminalGeneralResponse parse(byte[] body) {
        BodyReader reader = new BodyReader(body);
        TerminalGeneralResponse value = new TerminalGeneralResponse(
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
