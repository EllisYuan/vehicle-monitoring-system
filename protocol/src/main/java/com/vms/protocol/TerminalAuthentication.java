package com.vms.protocol;

/**
 * 终端鉴权（JT/T 808-2019 表 10，消息 ID 0x0102）。
 * 鉴权码长度 BYTE、鉴权码内容 STRING、终端 IMEI BYTE[15]、软件版本号 BYTE[20]。
 */
public record TerminalAuthentication(String authCode, String imei, String softwareVersion) {

    /** 表 10：IMEI 定长 15 字节。 */
    public static final int IMEI_LENGTH = 15;
    /** 表 10：软件版本号定长 20 字节，不足时后补 "0x00"。 */
    public static final int SOFTWARE_VERSION_LENGTH = 20;

    public static TerminalAuthentication parse(byte[] body) {
        BodyReader reader = new BodyReader(body);
        int codeLength = reader.readUnsignedByte();
        String code = reader.readString(codeLength);
        byte[] imeiBytes = reader.readBytes(IMEI_LENGTH);
        byte[] versionBytes = reader.readBytes(SOFTWARE_VERSION_LENGTH);
        reader.requireExhausted();
        return new TerminalAuthentication(code, Gbk.decode(imeiBytes), Gbk.decode(versionBytes));
    }

    public byte[] encode() {
        byte[] code = Gbk.encode(authCode);
        byte[] imeiBytes = Gbk.encode(imei);
        byte[] version = Gbk.encode(softwareVersion);
        if (imeiBytes.length != IMEI_LENGTH) {
            throw new IllegalArgumentException("IMEI 必须为 " + IMEI_LENGTH + " 字节");
        }
        if (version.length != SOFTWARE_VERSION_LENGTH) {
            throw new IllegalArgumentException("软件版本号必须为 " + SOFTWARE_VERSION_LENGTH + " 字节");
        }
        byte[] out = new byte[1 + code.length + IMEI_LENGTH + SOFTWARE_VERSION_LENGTH];
        out[0] = (byte) code.length;
        System.arraycopy(code, 0, out, 1, code.length);
        System.arraycopy(imeiBytes, 0, out, 1 + code.length, IMEI_LENGTH);
        System.arraycopy(version, 0, out, 1 + code.length + IMEI_LENGTH, SOFTWARE_VERSION_LENGTH);
        return out;
    }
}
