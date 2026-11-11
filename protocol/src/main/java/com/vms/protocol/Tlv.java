package com.vms.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 位置附加信息项（JT/T 808-2019 表 26）：附加信息 ID（BYTE）、附加信息长度（BYTE）、附加信息。
 */
public record Tlv(int id, byte[] value) {

    /** 表 26：附加信息长度字段为 1 字节。 */
    public static final int MAX_VALUE_LENGTH = 255;

    public Tlv {
        if (id < 1 || id > 255) {
            throw new IllegalArgumentException("附加信息 ID 超出 BYTE 范围：" + id);
        }
        if (value == null || value.length > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("附加信息长度必须在 0-" + MAX_VALUE_LENGTH + " 之间");
        }
        value = value.clone();
    }

    @Override
    public byte[] value() {
        return value.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Tlv tlv
                && id == tlv.id
                && java.util.Arrays.equals(value, tlv.value);
    }

    @Override
    public int hashCode() {
        return 31 * Integer.hashCode(id) + java.util.Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "Tlv[id=" + id + ", value=" + java.util.Arrays.toString(value) + "]";
    }

    public static List<Tlv> readAll(BodyReader reader) {
        List<Tlv> items = new ArrayList<>();
        while (reader.remaining() > 0) {
            int id = reader.readUnsignedByte();
            int length = reader.readUnsignedByte();
            try {
                items.add(new Tlv(id, reader.readBytes(length)));
            } catch (IllegalArgumentException e) {
                throw new MalformedFrameException("附加信息项非法：" + e.getMessage());
            }
        }
        return items;
    }

    public static void writeAll(List<Tlv> items, java.io.ByteArrayOutputStream out) {
        for (Tlv item : items) {
            out.write(item.id());
            out.write(item.value().length);
            out.writeBytes(item.value());
        }
    }
}
