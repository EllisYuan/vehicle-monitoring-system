package com.vms.protocol;

/**
 * 一帧 JT/T 808 报文（标识别位、消息头、消息体、校验码、标识位）。
 * 消息体保持原始字节，由各消息解析器按标准表格解释，避免 codec 与业务字段耦合。
 */
public record Frame(MessageHeader header, byte[] body) {

    /** 标识位（4.4.2）。 */
    public static final byte FLAG = 0x7E;

    public Frame {
        if (header == null) {
            throw new IllegalArgumentException("消息头不能为空");
        }
        if (body == null) {
            throw new IllegalArgumentException("消息体不能为 null，空消息体请用空数组");
        }
        if (body.length != header.bodyLength()) {
            throw new IllegalArgumentException(
                    "消息体属性声明 " + header.bodyLength() + " 字节，实际 " + body.length + " 字节");
        }
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public MessageType type() {
        return header.type();
    }
}
