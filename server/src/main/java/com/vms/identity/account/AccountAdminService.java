package com.vms.identity.account;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vms.audit.AuditService;
import com.vms.identity.security.PasswordHasher;
import com.vms.identity.security.RoleCode;
import com.vms.infrastructure.web.BadRequestException;
import com.vms.infrastructure.web.ConflictException;
import com.vms.infrastructure.web.NotFoundException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号与授权的管理操作仅由管理员执行；每次变更提升 auth_version，
 * 使旧会话在后续 HTTP 请求立即失效。
 */
@Service
public class AccountAdminService {

    private final UserAccountMapper users;
    private final UserFleetRepository userFleets;
    private final PasswordHasher passwordHasher;
    private final AuditService auditService;
    private final Clock clock;

    public AccountAdminService(UserAccountMapper users, UserFleetRepository userFleets,
                               PasswordHasher passwordHasher, AuditService auditService, Clock clock) {
        this.users = users;
        this.userFleets = userFleets;
        this.passwordHasher = passwordHasher;
        this.auditService = auditService;
        this.clock = clock;
    }

    public List<AccountView> list() {
        Map<Long, List<Long>> fleetsByUser = userFleets.findFleetIdsByUser();
        return users.selectList(new LambdaQueryWrapper<UserAccount>().orderByAsc(UserAccount::getId))
                .stream()
                .map(user -> AccountView.of(user, fleetsByUser.getOrDefault(user.getId(), List.of())))
                .toList();
    }

    @Transactional
    public AccountView create(UserAccount actor, String username, String rawPassword,
                              String roleCode, List<Long> fleetIds) {
        RoleCode role = RoleCode.parse(roleCode).orElseThrow(
                () -> new BadRequestException("INVALID_ROLE", "角色不合法"));
        List<Long> requestedFleets = fleetIds == null
                ? List.of()
                : fleetIds.stream().distinct().toList();
        if (role != RoleCode.FLEET_LEADER && !requestedFleets.isEmpty()) {
            throw new BadRequestException("ROLE_FLEET_MISMATCH", "只有车队长可以分配车队");
        }
        requireExistingFleets(requestedFleets);

        LocalDateTime now = LocalDateTime.now(clock);
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash(rawPassword));
        user.setRoleCode(role.name());
        user.setStatus("ENABLED");
        user.setAuthVersion(1);
        user.setFailedAttempts(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            users.insert(user);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("ACCOUNT_EXISTS", "用户名已存在");
        }
        if (!requestedFleets.isEmpty()) {
            userFleets.replace(user.getId(), requestedFleets, now);
        }
        auditService.record(AuditService.CATEGORY_ACCOUNT, "CREATE", "sys_user:" + user.getId(),
                AuditService.RESULT_SUCCESS, actor.getId(), actor.getUsername(), null,
                "role=" + role.name());
        if (!requestedFleets.isEmpty()) {
            auditService.record(AuditService.CATEGORY_AUTHORIZATION, "ASSIGN_FLEETS",
                    "sys_user:" + user.getId(), AuditService.RESULT_SUCCESS,
                    actor.getId(), actor.getUsername(), null, "fleetIds=" + requestedFleets);
        }
        return AccountView.of(user, requestedFleets);
    }

    @Transactional
    public AccountView updateStatus(UserAccount actor, long userId, String status) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BadRequestException("INVALID_STATUS", "账号状态不合法");
        }
        UserAccount user = requireUser(userId);
        if (user.getId().equals(actor.getId()) && "DISABLED".equals(status)) {
            throw new BadRequestException("SELF_DISABLE_FORBIDDEN", "不能停用当前登录账号");
        }
        users.updateStatusAndRevokeSessions(userId, status, LocalDateTime.now(clock));
        auditService.record(AuditService.CATEGORY_ACCOUNT, "UPDATE_STATUS", "sys_user:" + userId,
                AuditService.RESULT_SUCCESS, actor.getId(), actor.getUsername(), null,
                "status=" + status);
        return AccountView.of(requireUser(userId), userFleets.findFleetIds(userId));
    }

    @Transactional
    public void resetPassword(UserAccount actor, long userId, String newPassword) {
        requireUser(userId);
        users.updatePasswordAndRevokeSessions(userId, passwordHasher.hash(newPassword),
                LocalDateTime.now(clock));
        auditService.record(AuditService.CATEGORY_ACCOUNT, "RESET_PASSWORD", "sys_user:" + userId,
                AuditService.RESULT_SUCCESS, actor.getId(), actor.getUsername(), null, null);
    }

    @Transactional
    public AccountView assignFleets(UserAccount actor, long userId, List<Long> fleetIds) {
        UserAccount user = requireUser(userId);
        RoleCode role = RoleCode.parse(user.getRoleCode()).orElseThrow(
                () -> new IllegalStateException("未知角色：" + user.getRoleCode()));
        if (role != RoleCode.FLEET_LEADER) {
            throw new BadRequestException("ROLE_FLEET_MISMATCH", "只有车队长可以分配车队");
        }
        List<Long> requested = fleetIds == null
                ? List.of()
                : fleetIds.stream().distinct().toList();
        requireExistingFleets(requested);

        userFleets.replace(userId, requested, LocalDateTime.now(clock));
        users.revokeSessions(userId, LocalDateTime.now(clock));
        auditService.record(AuditService.CATEGORY_AUTHORIZATION, "ASSIGN_FLEETS", "sys_user:" + userId,
                AuditService.RESULT_SUCCESS, actor.getId(), actor.getUsername(), null,
                "fleetIds=" + requested);
        return AccountView.of(requireUser(userId), requested);
    }

    public UserAccount requireUser(long userId) {
        UserAccount user = users.selectById(userId);
        if (user == null) {
            throw new NotFoundException("USER_NOT_FOUND", "账号不存在");
        }
        return user;
    }

    private void requireExistingFleets(List<Long> fleetIds) {
        List<Long> distinct = fleetIds.stream().distinct().toList();
        if (userFleets.countExisting(distinct) != distinct.size()) {
            throw new BadRequestException("UNKNOWN_FLEET", "包含不存在的车队");
        }
    }

    public record AccountView(Long id, String username, String roleCode, String status,
                              List<Long> fleetIds) {
        static AccountView of(UserAccount user, List<Long> fleetIds) {
            return new AccountView(user.getId(), user.getUsername(), user.getRoleCode(),
                    user.getStatus(), fleetIds);
        }
    }
}
