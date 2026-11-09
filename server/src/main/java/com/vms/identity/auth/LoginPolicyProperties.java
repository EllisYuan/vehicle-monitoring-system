package com.vms.identity.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录保护默认值与 spec I4 一致：连续 5 次失败锁定 10 分钟；来源限速为工程补充。
 * 测试可缩短/改写参数，但生产默认值不变。
 */
@ConfigurationProperties(prefix = "vms.login")
public record LoginPolicyProperties(
        Integer maxFailures,
        Integer lockMinutes,
        Integer rateLimitMaxAttempts,
        Integer rateLimitWindowSeconds) {

    public LoginPolicyProperties {
        maxFailures = maxFailures == null ? 5 : maxFailures;
        lockMinutes = lockMinutes == null ? 10 : lockMinutes;
        rateLimitMaxAttempts = rateLimitMaxAttempts == null ? 20 : rateLimitMaxAttempts;
        rateLimitWindowSeconds = rateLimitWindowSeconds == null ? 60 : rateLimitWindowSeconds;
    }
}
