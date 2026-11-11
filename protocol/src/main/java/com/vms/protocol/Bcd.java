package com.vms.protocol;

/**
 * BCD 编解码（JT/T 808-2019 表 1：BCD[n] 为 8421 码，n 字节）。
 * 每个字节承载两位十进制数字，高四位在前。
 */
public final class Bcd {

    private Bcd() {
    }

    /**
     * 把十进制数字串编码为 BCD。长度必须为偶数，字符只能是 0-9。
     */
    public static byte[] encode(String digits) {
        if (digits == null) {
            throw new IllegalArgumentException("BCD 内容不能为空");
        }
        if (digits.length() % 2 != 0) {
            throw new IllegalArgumentException("BCD 编码要求偶数位数字，实际 " + digits.length());
        }
        byte[] out = new byte[digits.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = digit(digits.charAt(i * 2));
            int low = digit(digits.charAt(i * 2 + 1));
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    /**
     * 解码为十进制数字串；非 8421 半字节视为坏数据。
     */
    public static String decode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int high = (value >> 4) & 0x0F;
            int low = value & 0x0F;
            if (high > 9 || low > 9) {
                throw new IllegalArgumentException("非法 BCD 半字节：" + Integer.toHexString(value & 0xFF));
            }
            sb.append((char) ('0' + high)).append((char) ('0' + low));
        }
        return sb.toString();
    }

    private static int digit(char c) {
        if (c < '0' || c > '9') {
            throw new IllegalArgumentException("BCD 只接受十进制数字，实际为 '" + c + "'");
        }
        return c - '0';
    }
}
