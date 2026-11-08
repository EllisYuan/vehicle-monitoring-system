package com.vms.identity.auth;

/**
 * 账号不存在、密码错误与账号停用对外统一表现为凭证无效。
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("invalid credentials");
    }
}
