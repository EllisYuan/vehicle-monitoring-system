package com.vms.protocol;

/**
 * 本子集支持的消息 ID（JT/T 808-2019 第 8 章与附录 B 表 B.1）。
 * 未列出的消息按“未知消息”处理，不猜测语义。
 */
public enum MessageType {

    /** 表 4：终端通用应答。 */
    TERMINAL_GENERAL_RESPONSE(0x0001),
    /** 8.6：终端心跳，消息体为空。 */
    HEARTBEAT(0x0002),
    /** 8.10：终端注销，消息体为空。 */
    TERMINAL_LOGOUT(0x0003),
    /** 表 8：终端注册。 */
    TERMINAL_REGISTER(0x0100),
    /** 表 10：终端鉴权。 */
    TERMINAL_AUTHENTICATION(0x0102),
    /** 表 5：平台通用应答。 */
    PLATFORM_GENERAL_RESPONSE(0x8001),
    /** 表 9：终端注册应答。 */
    TERMINAL_REGISTER_RESPONSE(0x8100),
    /** 表 23-31：位置信息汇报。 */
    POSITION_REPORT(0x0200),
    /** 表 67-68：定位数据批量上传。 */
    POSITION_BATCH(0x0704),
    /** 表 37-38：文本信息下发。 */
    TEXT_ISSUE(0x8300),
    /** 表 34：临时位置跟踪控制。 */
    TEMPORARY_TRACKING(0x8202);

    private final int id;

    MessageType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    /**
     * 按消息 ID 查找；未知 ID 返回 null，由调用方决定拒绝或丢弃。
     */
    public static MessageType of(int id) {
        for (MessageType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return null;
    }
}
