package com.vms.identity.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.dev33.satoken.stp.StpUtil;
import com.vms.audit.AuditService;
import com.vms.identity.account.UserAccount;
import com.vms.identity.account.UserAccountMapper;
import com.vms.identity.security.PasswordHasher;
import com.vms.identity.security.Permission;
import com.vms.identity.security.RoleCode;
import com.vms.identity.security.ForbiddenException;
import com.vms.infrastructure.web.BadRequestException;
import com.vms.infrastructure.web.ConflictException;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 会话中保存登录时的授权版本；与库中不一致说明改密、重置、停用或撤权。 */
    public static final String SESSION_AUTH_VERSION = "authVersion";

    private final UserAccountMapper users;
    private final PasswordHasher passwordHasher;
    private final AuditService auditService;
    private final LoginPolicyProperties loginPolicy;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(UserAccountMapper users, PasswordHasher passwordHasher, AuditService auditService,
                       LoginPolicyProperties loginPolicy, Clock clock) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.auditService = auditService;
        this.loginPolicy = loginPolicy;
        this.clock = clock;
        this.dummyHash = passwordHasher.hash(UUID.randomUUID().toString());
    }

    public UserAccount authenticate(String username, String rawPassword, String sourceIp) {
        UserAccount user = users.selectOne(
                new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUsername, username));
        LocalDateTime now = LocalDateTime.now(clock);

        if (user == null) {
            // 与真实校验保持相近耗时，响应不区分账号是否存在
            passwordHasher.matches(rawPassword, dummyHash);
            auditFailure(null, username, sourceIp, "UNKNOWN_ACCOUNT");
            throw new InvalidCredentialsException();
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            // 锁定期间的响应与凭证错误一致，避免通过状态码判断账号是否存在
            auditFailure(user.getId(), username, sourceIp, "LOCKED");
            throw new InvalidCredentialsException();
        }
        if (!passwordHasher.matches(rawPassword, user.getPasswordHash())) {
            registerFailure(user.getId(), username, now, sourceIp);
            throw new InvalidCredentialsException();
        }
        if (!user.isEnabled()) {
            auditFailure(user.getId(), username, sourceIp, "ACCOUNT_DISABLED");
            throw new InvalidCredentialsException();
        }
        if (user.getFailedAttempts() != null && user.getFailedAttempts() > 0) {
            // 锁定前的成功登录重置连续失败计数；只改登录状态字段
            users.resetLoginState(user.getId(), now);
        }
        return user;
    }

    public void openSession(UserAccount user) {
        StpUtil.login(user.getId());
        // Token-Session 与具体 token 绑定，避免同账号登录会话共享版本值
        StpUtil.getTokenSession().set(SESSION_AUTH_VERSION, user.getAuthVersion());
    }

    /**
     * 每次请求校验账号仍启用且授权版本未变化；失效时立即撤销当前会话。
     */
    public UserAccount requireSession() {
        Long userId = StpUtil.getLoginIdAsLong();
        UserAccount user = users.selectById(userId);
        if (user == null || !user.isEnabled()) {
            StpUtil.logout();
            throw new SessionRevokedException("ACCOUNT_DISABLED");
        }
        Object sessionVersion = StpUtil.getTokenSession().get(SESSION_AUTH_VERSION);
        if (sessionVersion == null
                || !String.valueOf(user.getAuthVersion()).equals(String.valueOf(sessionVersion))) {
            StpUtil.logout();
            throw new SessionRevokedException("SESSION_REVOKED");
        }
        return user;
    }

    /** WS 和后台输出校验具体 token；不依赖 HTTP ThreadLocal，也不创建新的 session。 */
    public UserAccount requireToken(String token) {
        Object loginId = StpUtil.getLoginIdByToken(token);
        if (loginId == null) throw new SessionRevokedException("SESSION_EXPIRED");
        UserAccount user = users.selectById(Long.valueOf(loginId.toString()));
        if (user == null || !user.isEnabled()) throw new SessionRevokedException("ACCOUNT_DISABLED");
        var session = StpUtil.getStpLogic().getTokenSessionByToken(token, false);
        Object version = session == null ? null : session.get(SESSION_AUTH_VERSION);
        if (version == null || !String.valueOf(user.getAuthVersion()).equals(String.valueOf(version))) {
            throw new SessionRevokedException("SESSION_REVOKED");
        }
        return user;
    }

    public UserAccount currentUser() {
        return requireSession();
    }

    public void requirePermission(UserAccount user, Permission permission) {
        RoleCode role = RoleCode.parse(user.getRoleCode()).orElseThrow(ForbiddenException::new);
        if (!permission.isGrantedTo(role)) {
            throw new ForbiddenException();
        }
    }

    /**
     * 本人修改密码：撤销该账号全部会话（含当前会话），需重新登录。
     * 只更新密码与授权版本，避免覆盖并发的停用或授权变更。
     */
    @Transactional
    public void changeOwnPassword(UserAccount user, String oldPassword, String newPassword) {
        if (!passwordHasher.matches(oldPassword, user.getPasswordHash())) {
            throw new BadRequestException("INVALID_OLD_PASSWORD", "原密码不正确");
        }
        if (passwordHasher.matches(newPassword, user.getPasswordHash())) {
            throw new BadRequestException("PASSWORD_UNCHANGED", "新密码不能与原密码相同");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (users.changePasswordIfCurrent(user.getId(), user.getAuthVersion(),
                passwordHasher.hash(newPassword), now) != 1) {
            throw new ConflictException("ACCOUNT_CHANGED", "账号状态已变更，请重新登录后操作");
        }
        auditService.record(AuditService.CATEGORY_ACCOUNT, "CHANGE_PASSWORD", "sys_user:" + user.getId(),
                AuditService.RESULT_SUCCESS, user.getId(), user.getUsername(), null, null);
    }

    public void auditLoginSuccess(UserAccount user, String sourceIp) {
        auditService.record(AuditService.CATEGORY_LOGIN, "LOGIN", "auth",
                AuditService.RESULT_SUCCESS, user.getId(), user.getUsername(), sourceIp, null);
    }

    public void auditLogout(UserAccount user, String sourceIp) {
        auditService.record(AuditService.CATEGORY_LOGOUT, "LOGOUT", "auth",
                AuditService.RESULT_SUCCESS, user.getId(), user.getUsername(), sourceIp, null);
    }

    /**
     * 单条 SQL 原子自增失败次数并在达到阈值时写入锁定时间，
     * 并发错误尝试不会因读-改-写丢失计数。
     */
    private void registerFailure(Long userId, String username, LocalDateTime now, String sourceIp) {
        LocalDateTime lockUntil = now.plusMinutes(loginPolicy.lockMinutes());
        users.incrementFailureAndMaybeLock(userId, now, loginPolicy.maxFailures(), lockUntil);
        UserAccount updated = users.selectById(userId);
        boolean locked = updated != null && updated.getLockedUntil() != null
                && updated.getLockedUntil().isAfter(now);
        auditFailure(userId, username, sourceIp,
                locked ? "BAD_CREDENTIALS_LOCKED" : "BAD_CREDENTIALS");
        if (locked) {
            auditService.record(AuditService.CATEGORY_LOGIN, "LOCK", "sys_user:" + userId,
                    AuditService.RESULT_SUCCESS, userId, username, sourceIp,
                    "lockedUntil=" + updated.getLockedUntil());
        }
    }

    private void auditFailure(Long actorId, String username, String sourceIp, String reason) {
        auditService.record(AuditService.CATEGORY_LOGIN, "LOGIN", "auth",
                AuditService.RESULT_FAILURE, actorId, username, sourceIp, reason);
        log.warn("登录失败：账号 {}，原因 {}", username, reason);
    }
}
