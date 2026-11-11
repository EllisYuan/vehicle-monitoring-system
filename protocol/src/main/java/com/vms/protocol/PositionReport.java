package com.vms.protocol;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
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

    private static final DateTimeFormatter TIME_FORMAT = new DateTimeFormatterBuilder()
            .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
            .appendPattern("MMddHHmmss")
            .toFormatter()
            .withResolverStyle(ResolverStyle.STRICT);

    public PositionReport {
        if (attachments == null || time == null || !Double.isFinite(latitude)
                || !Double.isFinite(longitude) || Math.abs(latitude) > 90
                || Math.abs(longitude) > 180 || alarmFlag < 0 || alarmFlag > 0xFFFF_FFFFL
                || status < 0 || status > 0xFFFF_FFFFL || altitude < 0 || altitude > 0xFFFF
                || speed < 0 || speed > 0xFFFF || direction < 0 || direction > 359) {
            throw new IllegalArgumentException("位置信息字段超出协议范围");
        }
        if ((latitude != 0 && (latitude < 0) != ((status & 0x04) != 0))
                || (longitude != 0 && (longitude < 0) != ((status & 0x08) != 0))) {
            throw new IllegalArgumentException("经纬度符号与状态位不一致");
        }
        if (time.getYear() < 2000 || time.getYear() > 2099) {
            throw new IllegalArgumentException("位置时间超出 YY 范围");
        }
        attachments = List.copyOf(attachments);
    }

    public static PositionReport parse(byte[] body) {
        BodyReader reader = new BodyReader(body);
        long alarm = reader.readUnsignedInt();
        long status = reader.readUnsignedInt();
        double latitude = reader.readUnsignedInt() / 1_000_000.0;
        double longitude = reader.readUnsignedInt() / 1_000_000.0;
        if ((status & 0x04) != 0) {
            latitude = -latitude;
        }
        if ((status & 0x08) != 0) {
            longitude = -longitude;
        }
        int altitude = reader.readUnsignedShort();
        int speed = reader.readUnsignedShort();
        int direction = reader.readUnsignedShort();
        LocalDateTime time;
        try {
            time = LocalDateTime.parse(reader.readBcd(6), TIME_FORMAT);
        } catch (java.time.DateTimeException e) {
            throw new MalformedFrameException("位置时间非法：" + e.getMessage());
        }
        List<Tlv> attachments = Tlv.readAll(reader);
        try {
            return new PositionReport(alarm, status, latitude, longitude, altitude, speed, direction, time,
                    attachments);
        } catch (IllegalArgumentException e) {
            throw new MalformedFrameException("位置信息非法：" + e.getMessage());
        }
    }

    public byte[] encode() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] head = new byte[BASIC_LENGTH];
        Bytes.putUnsignedInt(head, 0, alarmFlag);
        Bytes.putUnsignedInt(head, 4, status);
        Bytes.putUnsignedInt(head, 8, Math.round(Math.abs(latitude) * 1_000_000));
        Bytes.putUnsignedInt(head, 12, Math.round(Math.abs(longitude) * 1_000_000));
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
