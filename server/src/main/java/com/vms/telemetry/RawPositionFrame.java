package com.vms.telemetry;

import com.vms.protocol.Bcd;
import com.vms.protocol.BodyReader;
import com.vms.protocol.MalformedFrameException;
import com.vms.protocol.PositionReport;
import com.vms.protocol.Tlv;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.List;

/**
 * 0x0200 基本体的宽松解析：长度/结构错误仍抛 {@link MalformedFrameException} 关闭连接，
 * 非法时间与越界坐标只标记为质量问题，由位置分流记录异常并成功应答。
 */
record RawPositionFrame(long alarmFlag, long statusFlag, long latitudeRaw, long longitudeRaw,
                        int altitude, int speed, int direction, LocalDateTime gpsTime,
                        List<Tlv> attachments, Problem problem) {

    enum Problem { INVALID_TIME, COORDINATE_OUT_OF_RANGE }

    private static final DateTimeFormatter TIME_FORMAT = new DateTimeFormatterBuilder()
            .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
            .appendPattern("MMddHHmmss")
            .toFormatter()
            .withResolverStyle(ResolverStyle.STRICT);

    static RawPositionFrame parse(byte[] body) {
        BodyReader reader = new BodyReader(body);
        long alarm = reader.readUnsignedInt();
        long status = reader.readUnsignedInt();
        long latitudeRaw = reader.readUnsignedInt();
        long longitudeRaw = reader.readUnsignedInt();
        int altitude = reader.readUnsignedShort();
        int speed = reader.readUnsignedShort();
        int direction = reader.readUnsignedShort();
        byte[] timeBytes = reader.readBytes(6);
        LocalDateTime time = null;
        boolean badTime = false;
        try {
            time = LocalDateTime.parse(Bcd.decode(timeBytes), TIME_FORMAT);
        } catch (MalformedFrameException | java.time.DateTimeException e) {
            badTime = true;
        }
        List<Tlv> attachments = Tlv.readAll(reader);
        if (badTime) {
            return new RawPositionFrame(alarm, status, latitudeRaw, longitudeRaw, altitude, speed, direction,
                    null, List.of(), Problem.INVALID_TIME);
        }
        double latitude = signed(latitudeRaw, (status & 0x04) != 0);
        double longitude = signed(longitudeRaw, (status & 0x08) != 0);
        Problem problem = Math.abs(latitude) > 90 || Math.abs(longitude) > 180
                ? Problem.COORDINATE_OUT_OF_RANGE : null;
        return new RawPositionFrame(alarm, status, latitudeRaw, longitudeRaw, altitude, speed, direction,
                time, attachments, problem);
    }

    PositionReport toReport() {
        return new PositionReport(alarmFlag, statusFlag, signed(latitudeRaw, (statusFlag & 0x04) != 0),
                signed(longitudeRaw, (statusFlag & 0x08) != 0), altitude, speed, direction, gpsTime, attachments);
    }

    private static double signed(long raw, boolean negative) {
        double value = raw / 1_000_000.0;
        return negative ? -value : value;
    }
}
