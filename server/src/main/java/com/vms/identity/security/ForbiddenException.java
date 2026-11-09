package com.vms.identity.security;

/**
 * 功能权限不足：服务端明确拒绝，不回传任何业务数据。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException() {
        super("forbidden");
    }
}
