package com.vms.protocol;

/**
 * 消息体属性（JT/T 808-2019 消息体属性格式结构图）。
 * <ul>
 *   <li>bit0-9：消息体长度</li>
 *   <li>bit10-12：数据加密方式，bit10 为 1 表示 RSA 加密</li>
 *   <li>bit13：分包标志，1 表示包含消息包封装项</li>
 *   <li>bit14：版本标识，0 为 2011 版，1 为引入版本标识的版本</li>
 * </ul>
 * 本子集只支持不分包、不加密的 2019 版报文。
 */
public record BodyProperties(int rawLength, boolean encrypted, boolean packaged, boolean versionFlag) {

    /** 消息体长度上限：10 位字段的自然上限。 */
    public static final int MAX_BODY_LENGTH = 1023;

    public BodyProperties {
        if (rawLength < 0 || rawLength > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("消息体长度超出 10 位字段范围：" + rawLength);
        }
    }

    public static BodyProperties of(int value) {
        // bit10-12 为三位加密标识：全 0 表示不加密，bit10=1 表示 RSA 加密（4.4.3.3）
        int encryption = (value >> 10) & 0x07;
        return new BodyProperties(
                value & 0x03FF,
                encryption != 0,
                (value & (1 << 13)) != 0,
                (value & (1 << 14)) != 0);
    }

    public static BodyProperties plain(int bodyLength) {
        return new BodyProperties(bodyLength, false, false, true);
    }

    public int toInt() {
        int value = rawLength;
        if (encrypted) {
            value |= 1 << 10;
        }
        if (packaged) {
            value |= 1 << 13;
        }
        if (versionFlag) {
            value |= 1 << 14;
        }
        return value;
    }
}
