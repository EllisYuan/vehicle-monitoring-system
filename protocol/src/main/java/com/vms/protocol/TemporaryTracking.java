package com.vms.protocol;

/**
 * 临时位置跟踪控制（JT/T 808-2019 表 34，消息 ID 0x8202）。
 * 时间间隔 WORD（秒，为 0 时停止跟踪且无需带后续字段）、位置跟踪有效期 DWORD（秒）。
 */
public record TemporaryTracking(int intervalSeconds, long validSeconds) {

    public static TemporaryTracking parse(byte[] body) {
        BodyReader reader = new BodyReader(body);
        int interval = reader.readUnsignedShort();
        // 表 34：间隔为 0 表示停止跟踪，停止跟踪无需带后续字段
        if (interval == 0) {
            reader.requireExhausted();
            return new TemporaryTracking(0, 0);
        }
        long valid = reader.readUnsignedInt();
        reader.requireExhausted();
        return new TemporaryTracking(interval, valid);
    }

    public byte[] encode() {
        if (intervalSeconds == 0) {
            return new byte[] { 0, 0 };
        }
        byte[] out = new byte[6];
        Bytes.putUnsignedShort(out, 0, intervalSeconds);
        Bytes.putUnsignedInt(out, 2, validSeconds);
        return out;
    }

    /** 表 34：间隔为 0 表示停止跟踪。 */
    public boolean stop() {
        return intervalSeconds == 0;
    }
}
