package com.vms.protocol;

/**
 * 可识别但本实现不支持的报文（分包、加密、未知消息），带明确原因以便规范拒绝。
 */
public class UnsupportedMessageException extends RuntimeException {

    private final String code;

    public UnsupportedMessageException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
