package com.vms.protocol;

/**
 * 终端注册应答（JT/T 808-2019 表 9，消息 ID 0x8100）。
 * 应答流水号 WORD、结果 BYTE（0 成功；1 车辆已注册；2 数据库无该车辆；3 终端已被注册；4 数据库中无该终端）、
 * 鉴权码 STRING（注册结果为成功时才有该字段）。
 */
public record TerminalRegisterResponse(int responseSerial, int result, String authCode) {

    public static final int RESULT_SUCCESS = 0;
    public static final int RESULT_VEHICLE_REGISTERED = 1;
    public static final int RESULT_NO_VEHICLE = 2;
    public static final int RESULT_TERMINAL_REGISTERED = 3;
    public static final int RESULT_NO_TERMINAL = 4;

    public static TerminalRegisterResponse parse(byte[] body) {
        BodyReader reader = new BodyReader(body);
        int serial = reader.readUnsignedShort();
        int result = reader.readUnsignedByte();
        String authCode = result == RESULT_SUCCESS ? reader.readString(reader.remaining()) : null;
        reader.requireExhausted();
        return new TerminalRegisterResponse(serial, result, authCode);
    }

    public byte[] encode() {
        byte[] code = authCode == null ? new byte[0] : Gbk.encode(authCode);
        byte[] out = new byte[3 + code.length];
        Bytes.putUnsignedShort(out, 0, responseSerial);
        out[2] = (byte) result;
        System.arraycopy(code, 0, out, 3, code.length);
        return out;
    }
}
