package com.vms.identity.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.dev33.satoken.stp.StpUtil;
import com.vms.audit.AuditService;
import com.vms.identity.account.UserAccount;
import com.vms.identity.account.UserAccountMapper;
import com.vms.identity.security.PasswordHasher;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserAccountMapper users;
    private final PasswordHasher passwordHasher;
    private final AuditService auditService;
    private final String dummyHash;

    public AuthService(UserAccountMapper users, PasswordHasher passwordHasher, AuditService auditService) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.auditService = auditService;
        this.dummyHash = passwordHasher.hash(UUID.randomUUID().toString());
    }

    public UserAccount authenticate(String username, String rawPassword, String sourceIp) {
        UserAccount user = users.selectOne(
                new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUsername, username));

        if (user == null) {
            // 与真实校验保持相近耗时，响应不区分账号是否存在
            passwordHasher.matches(rawPassword, dummyHash);
            auditFailure(null, username, sourceIp, "UNKNOWN_ACCOUNT");
            throw new InvalidCredentialsException();
        }
        if (!passwordHasher.matches(rawPassword, user.getPasswordHash())) {
            auditFailure(user.getId(), username, sourceIp, "BAD_CREDENTIALS");
            throw new InvalidCredentialsException();
        }
        if (!user.isEnabled()) {
            auditFailure(user.getId(), username, sourceIp, "ACCOUNT_DISABLED");
            throw new InvalidCredentialsException();
        }
        return user;
    }

    public UserAccount currentUser() {
        Long userId = StpUtil.getLoginIdAsLong();
        UserAccount user = users.selectById(userId);
        if (user == null) {
            throw new InvalidCredentialsException();
        }
        return user;
    }

    public void auditLoginSuccess(UserAccount user, String sourceIp) {
        auditService.record(AuditService.CATEGORY_LOGIN, "LOGIN", "auth",
                AuditService.RESULT_SUCCESS, user.getId(), user.getUsername(), sourceIp, null);
    }

    public void auditLogout(UserAccount user, String sourceIp) {
        auditService.record(AuditService.CATEGORY_LOGOUT, "LOGOUT", "auth",
                AuditService.RESULT_SUCCESS, user.getId(), user.getUsername(), sourceIp, null);
    }

    private void auditFailure(Long actorId, String username, String sourceIp, String reason) {
        auditService.record(AuditService.CATEGORY_LOGIN, "LOGIN", "auth",
                AuditService.RESULT_FAILURE, actorId, username, sourceIp, reason);
        log.warn("登录失败：账号 {}，原因 {}", username, reason);
    }
}
