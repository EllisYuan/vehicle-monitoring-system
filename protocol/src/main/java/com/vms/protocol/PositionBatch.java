package com.vms.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 定位数据批量上传（JT/T 808-2019 8.48，表 67、表 68，消息 ID 0x0704）。
 * <p>
 * 结构为：数据项个数 WORD、位置数据类型 BYTE（0 正常批量，1 盲区补报）、
 * 之后每项为“位置汇报数据长度 WORD + 位置汇报数据 BYTE[n]”。
 * <p>
 * 每包最多 20 项；项数与每项声明长度都必须与实际字节一致，否则整包拒绝，
 * 不做部分解析。
 */
public record PositionBatch(int dataType, List<PositionReport> items) {

    /** 本子集限制：每包最多 20 个定位点。 */
    public static final int MAX_ITEMS = 20;

    public static final int TYPE_NORMAL = 0;
    public static final int TYPE_BLIND_AREA = 1;

    public PositionBatch {
        if (items == null || items.isEmpty()) {
            throw new MalformedFrameException("0x0704 数据项不能为空");
        }
        if (items.size() > MAX_ITEMS) {
            throw new MalformedFrameException("0x0704 每包最多 " + MAX_ITEMS + " 项");
        }
        if (dataType != TYPE_NORMAL && dataType != TYPE_BLIND_AREA) {
            throw new MalformedFrameException("0x0704 位置数据类型未知：" + dataType);
        }
        items = List.copyOf(items);
    }

    public static PositionBatch parse(byte[] body) {
        BodyReader reader = new BodyReader(body);
        int count = reader.readUnsignedShort();
        if (count <= 0) {
            throw new MalformedFrameException("0x0704 数据项个数必须大于 0");
        }
        if (count > MAX_ITEMS) {
            throw new MalformedFrameException(
                    "0x0704 每包最多 " + MAX_ITEMS + " 项，实际声明 " + count + " 项");
        }
        int dataType = reader.readUnsignedByte();
        if (dataType != TYPE_NORMAL && dataType != TYPE_BLIND_AREA) {
            throw new MalformedFrameException("0x0704 位置数据类型未知：" + dataType);
        }
        List<PositionReport> items = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int length = reader.readUnsignedShort();
            // 每项必须是完整的位置汇报；长度不足基本字段说明声明与实际不符
            if (length < PositionReport.BASIC_LENGTH) {
                throw new MalformedFrameException(
                        "0x0704 第 " + (index + 1) + " 项长度 " + length + " 小于基本位置信息长度");
            }
            items.add(PositionReport.parse(reader.readBytes(length)));
        }
        reader.requireExhausted();
        return new PositionBatch(dataType, items);
    }

    public byte[] encode() {
        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("0x0704 每包最多 " + MAX_ITEMS + " 项");
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] head = new byte[3];
        Bytes.putUnsignedShort(head, 0, items.size());
        head[2] = (byte) dataType;
        out.writeBytes(head);
        for (PositionReport item : items) {
            byte[] bytes = item.encode();
            byte[] length = new byte[2];
            Bytes.putUnsignedShort(length, 0, bytes.length);
            out.writeBytes(length);
            out.writeBytes(bytes);
        }
        if (out.size() > BodyProperties.MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("0x0704 消息体超过 1023 字节");
        }
        return out.toByteArray();
    }
}
