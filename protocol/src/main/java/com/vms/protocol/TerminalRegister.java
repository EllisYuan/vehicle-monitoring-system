package com.vms.protocol;

/**
 * 终端注册（JT/T 808-2019 表 8，消息 ID 0x0100）。
 * 省域 ID WORD、市县域 ID WORD、制造商 ID BYTE[11]、终端型号 BYTE[30]、
 * 终端 ID BYTE[30]、车牌颜色 BYTE、车牌 STRING。
 */
public record TerminalRegister(
        int provinceId,
        int cityId,
        String manufacturer,
        String model,
        String terminalId,
        int plateColor,
        String plateNo) {

    /** 表 8：制造商 ID BYTE[11]（行政区划代码 + 制造商 ID）。 */
    public static final int MANUFACTURER_LENGTH = 11;
    public static final int MODEL_LENGTH = 30;
    public static final int TERMINAL_ID_LENGTH = 30;

    public static TerminalRegister parse(byte[] body) {
        BodyReader reader = new BodyReader(body);
        int province = reader.readUnsignedShort();
        int city = reader.readUnsignedShort();
        String manufacturer = trimNul(reader.readString(MANUFACTURER_LENGTH));
        String model = trimLeadingNul(reader.readString(MODEL_LENGTH));
        if (model.indexOf('\u0000') >= 0) {
            throw new MalformedFrameException("终端型号前补位后含非法 0x00");
        }
        String terminalId = trimNul(reader.readString(TERMINAL_ID_LENGTH));
        int plateColor = reader.readUnsignedByte();
        String plateNo = reader.readString(reader.remaining());
        return new TerminalRegister(province, city, manufacturer, model, terminalId, plateColor, plateNo);
    }

    public byte[] encode() {
        byte[] manufacturerBytes = fixed(manufacturer, MANUFACTURER_LENGTH);
        byte[] modelBytes = fixedModel(model);
        byte[] terminalIdBytes = fixed(terminalId, TERMINAL_ID_LENGTH);
        byte[] plateBytes = Gbk.encode(plateNo);
        byte[] out = new byte[4 + MANUFACTURER_LENGTH + MODEL_LENGTH + TERMINAL_ID_LENGTH + 1
                + plateBytes.length];
        Bytes.putUnsignedShort(out, 0, provinceId);
        Bytes.putUnsignedShort(out, 2, cityId);
        System.arraycopy(manufacturerBytes, 0, out, 4, MANUFACTURER_LENGTH);
        System.arraycopy(modelBytes, 0, out, 4 + MANUFACTURER_LENGTH, MODEL_LENGTH);
        System.arraycopy(terminalIdBytes, 0, out,
                4 + MANUFACTURER_LENGTH + MODEL_LENGTH, TERMINAL_ID_LENGTH);
        out[4 + MANUFACTURER_LENGTH + MODEL_LENGTH + TERMINAL_ID_LENGTH] = (byte) plateColor;
        System.arraycopy(plateBytes, 0, out,
                5 + MANUFACTURER_LENGTH + MODEL_LENGTH + TERMINAL_ID_LENGTH, plateBytes.length);
        return out;
    }

    private static String trimNul(String text) {
        int end = text.indexOf('\u0000');
        return end < 0 ? text : text.substring(0, end);
    }

    private static String trimLeadingNul(String text) {
        int start = 0;
        while (start < text.length() && text.charAt(start) == '\u0000') {
            start++;
        }
        return text.substring(start);
    }

    private static byte[] fixedModel(String text) {
        byte[] bytes = Gbk.encode(text);
        if (text.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("终端型号不能包含 0x00");
        }
        if (bytes.length > MODEL_LENGTH) {
            throw new IllegalArgumentException("终端型号超过 " + MODEL_LENGTH + " 字节");
        }
        byte[] out = new byte[MODEL_LENGTH];
        System.arraycopy(bytes, 0, out, MODEL_LENGTH - bytes.length, bytes.length);
        return out;
    }

    /** 表 8：制造商与终端 ID 不足时后补 0x00。 */
    private static byte[] fixed(String text, int length) {
        byte[] bytes = Gbk.encode(text);
        if (bytes.length > length) {
            throw new IllegalArgumentException("字段超过定长 " + length + " 字节");
        }
        byte[] out = new byte[length];
        System.arraycopy(bytes, 0, out, 0, bytes.length);
        return out;
    }
}
