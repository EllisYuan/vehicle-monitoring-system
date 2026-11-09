package com.vms.identity.auth;

/**
 * 会话基础仍存在，但账号状态或授权版本已变化；旧会话立即失效。
 */
public class SessionRevokedException extends RuntimeException {

    private final String code;

    public SessionRevokedException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
