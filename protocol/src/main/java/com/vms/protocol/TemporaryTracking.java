package com.vms.protocol;

/**
 * 临时位置跟踪控制（JT/T 808-2019 表 34，消息 ID 0x8202）。
 * 时间间隔 WORD（秒，为 0 时停止跟踪且无需带后续字段）、位置跟踪有效期 DWORD（秒）。
 */
public record TemporaryTracking(int intervalSeconds, long validSeconds) {

    public TemporaryTracking {
        if (intervalSeconds == 0) {
            if (validSeconds != 0) {
                throw new IllegalArgumentException("停止跟踪时有效期必须为 0");
            }
        } else {
            if (intervalSeconds < 1 || intervalSeconds > 60) {
                throw new IllegalArgumentException("跟踪间隔必须在 1-60 秒之间");
            }
            if (validSeconds < 0 || validSeconds > 3600) {
                throw new IllegalArgumentException("跟踪有效期必须在 0-3600 秒之间");
            }
        }
    }

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
        try {
            return new TemporaryTracking(interval, valid);
        } catch (IllegalArgumentException e) {
            throw new MalformedFrameException("临时跟踪参数非法：" + e.getMessage());
        }
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
