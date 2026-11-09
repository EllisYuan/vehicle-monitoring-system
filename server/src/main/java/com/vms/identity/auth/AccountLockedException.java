package com.vms.identity.auth;

import java.time.Duration;

/**
 * 连续失败达到阈值后的临时锁定；响应给出可重试时间，但不揭示凭证是否正确。
 */
public class AccountLockedException extends RuntimeException {

    private final Duration retryAfter;

    public AccountLockedException(Duration retryAfter) {
        super("account locked");
        this.retryAfter = retryAfter;
    }

    public long retryAfterSeconds() {
        long seconds = retryAfter.getSeconds();
        return Math.max(seconds, 1);
    }
}
