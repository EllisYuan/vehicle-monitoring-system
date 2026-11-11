package com.vms.protocol;

/**
 * 无法安全解析的报文。调用方据此丢弃或记录，不能基于不可信头部拼接应答。
 */
public class MalformedFrameException extends RuntimeException {

    public MalformedFrameException(String message) {
        super(message);
    }
}
