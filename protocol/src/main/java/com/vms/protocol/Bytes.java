package com.vms.protocol;

/**
 * 大端字节工具。协议 4.3 规定字节序为大端：WORD 先高八位再低八位，DWORD 依次高二十四位到低八位。
 */
final class Bytes {

    private Bytes() {
    }

    static int unsignedByte(byte[] data, int offset) {
        return data[offset] & 0xFF;
    }

    static int unsignedShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    static long unsignedInt(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    static void putUnsignedShort(byte[] out, int offset, int value) {
        out[offset] = (byte) ((value >> 8) & 0xFF);
        out[offset + 1] = (byte) (value & 0xFF);
    }

    static void putUnsignedInt(byte[] out, int offset, long value) {
        out[offset] = (byte) ((value >> 24) & 0xFF);
        out[offset + 1] = (byte) ((value >> 16) & 0xFF);
        out[offset + 2] = (byte) ((value >> 8) & 0xFF);
        out[offset + 3] = (byte) (value & 0xFF);
    }
}
