package com.vms.protocol;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 位置信息汇报（JT/T 808-2019 8.21，表 23、表 26-31，消息 ID 0x0200）。
 * <p>
 * 基本位置信息为定长 28 字节：报警标志 DWORD、状态 DWORD、纬度 DWORD、经度 DWORD、
 * 高程 WORD、速度 WORD、方向 WORD、时间 BCD[6]；其后为可选的附加信息项列表。
 * 纬度、经度以百万分之一度为单位，时间格式为 YY-MM-DD-hh-mm-ss（GMT+8）。
 */
public record PositionReport(
        long alarmFlag,
        long status,
        double latitude,
        double longitude,
        int altitude,
        int speed,
        int direction,
        LocalDateTime time,
        List<Tlv> attachments) {

    /** 表 23：基本位置信息定长 28 字节。 */
    public static final int BASIC_LENGTH = 28;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    public PositionReport {
        attachments = List.copyOf(attachments);
    }

    public static PositionReport parse(byte[] body) {
        BodyReader reader = new BodyReader(body);
        long alarm = reader.readUnsignedInt();
        long status = reader.readUnsignedInt();
        // 百万分之一度：以有符号 32 位解释，南纬与西经为负
        double latitude = (int) reader.readUnsignedInt() / 1_000_000.0;
        double longitude = (int) reader.readUnsignedInt() / 1_000_000.0;
        int altitude = reader.readUnsignedShort();
        int speed = reader.readUnsignedShort();
        int direction = reader.readUnsignedShort();
        LocalDateTime time = LocalDateTime.parse(reader.readBcd(6), TIME_FORMAT);
        List<Tlv> attachments = Tlv.readAll(reader);
        return new PositionReport(alarm, status, latitude, longitude, altitude, speed, direction, time,
                attachments);
    }

    public byte[] encode() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] head = new byte[BASIC_LENGTH];
        Bytes.putUnsignedInt(head, 0, alarmFlag);
        Bytes.putUnsignedInt(head, 4, status);
        Bytes.putUnsignedInt(head, 8, Math.round(latitude * 1_000_000));
        Bytes.putUnsignedInt(head, 12, Math.round(longitude * 1_000_000));
        Bytes.putUnsignedShort(head, 16, altitude);
        Bytes.putUnsignedShort(head, 18, speed);
        Bytes.putUnsignedShort(head, 20, direction);
        System.arraycopy(Bcd.encode(time.format(TIME_FORMAT)), 0, head, 22, 6);
        out.writeBytes(head);
        Tlv.writeAll(attachments, out);
        return out.toByteArray();
    }

    /** 依据状态位 bit1 判断定位是否有效（表 24）。 */
    public boolean fixValid() {
        return (status & 0x02) != 0;
    }

    /** 依据状态位 bit1 判断南北纬（表 24：0 北纬，1 南纬）。 */
    public boolean south() {
        return (status & 0x04) != 0;
    }

    /** 依据状态位 bit2 判断东西经（表 24：0 东经，1 西经）。 */
    public boolean west() {
        return (status & 0x08) != 0;
    }
}
