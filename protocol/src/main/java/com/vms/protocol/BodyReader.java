package com.vms.protocol;

import java.util.Arrays;

/**
 * 按大端顺序读取消息体的游标。越界一律抛 {@link MalformedFrameException}，
 * 保证解析失败时不会产出半截对象。
 */
public final class BodyReader {

    private final byte[] data;
    private int position;

    public BodyReader(byte[] data) {
        this.data = data.clone();
    }

    public int remaining() {
        return data.length - position;
    }

    public int position() {
        return position;
    }

    public int readUnsignedByte() {
        require(1);
        return Bytes.unsignedByte(data, position++);
    }

    public int readUnsignedShort() {
        require(2);
        int value = Bytes.unsignedShort(data, position);
        position += 2;
        return value;
    }

    public long readUnsignedInt() {
        require(4);
        long value = Bytes.unsignedInt(data, position);
        position += 4;
        return value;
    }

    public byte[] readBytes(int length) {
        if (length < 0) {
            throw new MalformedFrameException("读取长度不能为负：" + length);
        }
        require(length);
        byte[] out = Arrays.copyOfRange(data, position, position + length);
        position += length;
        return out;
    }

    /** 读 BCD[n]，返回十进制数字串。 */
    public String readBcd(int byteLength) {
        return Bcd.decode(readBytes(byteLength));
    }

    /** 读 STRING：GBK 编码，长度由调用方按标准给出或显式传入。 */
    public String readString(int byteLength) {
        return Gbk.decode(readBytes(byteLength));
    }

    public byte[] readRemaining() {
        return readBytes(remaining());
    }

    /**
     * 报文解析完成后必须无剩余字节，否则说明长度字段与实际结构不符。
     */
    public void requireExhausted() {
        if (remaining() != 0) {
            throw new MalformedFrameException("消息体有多余的 " + remaining() + " 字节");
        }
    }

    private void require(int length) {
        if (remaining() < length) {
            throw new MalformedFrameException(
                    "消息体剩余 " + remaining() + " 字节，不足以读取 " + length + " 字节");
        }
    }
}
