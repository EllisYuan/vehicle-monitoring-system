package com.vms.identity.auth;

import java.time.Duration;

/**
 * 来源速率限制：按来源 IP 限制单位时间内的登录尝试，避免凭证猜测和针对单个账号的锁定滥用。
 */
public class LoginRateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    public LoginRateLimitedException(Duration retryAfter) {
        super("login rate limited");
        this.retryAfter = retryAfter;
    }

    public long retryAfterSeconds() {
        long seconds = retryAfter.getSeconds();
        return Math.max(seconds, 1);
    }
}
