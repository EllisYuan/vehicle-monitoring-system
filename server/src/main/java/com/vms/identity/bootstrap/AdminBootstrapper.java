package com.vms.identity.bootstrap;

import com.vms.audit.AuditService;
import com.vms.identity.security.PasswordHasher;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 初始管理员只从部署配置一次性建立；既有账号永不因重启或配置变化被改写。
 */
@Component
public class AdminBootstrapper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapper.class);
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final JdbcTemplate jdbc;
    private final BootstrapAdminProperties properties;
    private final PasswordHasher passwordHasher;
    private final AuditService auditService;
    private final Clock clock;

    public AdminBootstrapper(JdbcTemplate jdbc, BootstrapAdminProperties properties, PasswordHasher passwordHasher,
                             AuditService auditService, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.passwordHasher = passwordHasher;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (countUsers() > 0) {
            log.info("账号已存在，跳过初始管理员建立");
            return;
        }
        if (!properties.isConfigured()) {
            throw new IllegalStateException(
                    "空数据库需要初始管理员配置：请设置 VMS_BOOTSTRAP_ADMIN_USERNAME 与 VMS_BOOTSTRAP_ADMIN_PASSWORD");
        }
        validate(properties.username(), properties.password());

        LocalDateTime now = LocalDateTime.now(clock);
        String passwordHash = passwordHasher.hash(properties.password());
        try {
            jdbc.update("""
                            INSERT INTO sys_user
                                (username, password_hash, role_code, status, auth_version, created_at, updated_at)
                            VALUES (?, ?, 'ADMIN', 'ENABLED', 1, ?, ?)
                            """,
                    properties.username(), passwordHash, now, now);
        } catch (DuplicateKeyException e) {
            log.info("初始管理员已由其他启动流程建立，跳过");
            return;
        }

        Long adminId = jdbc.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, properties.username());
        auditService.record(AuditService.CATEGORY_BOOTSTRAP, "INITIAL_ADMIN_CREATED",
                "sys_user:" + adminId, AuditService.RESULT_SUCCESS,
                null, properties.username(), null, "角色 ADMIN");
        log.info("初始管理员已建立：{}", properties.username());
    }

    private long countUsers() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM sys_user", Long.class);
        return count == null ? 0 : count;
    }

    private void validate(String username, String password) {
        if (username.length() > 64) {
            throw new IllegalStateException("VMS_BOOTSTRAP_ADMIN_USERNAME 不能超过 64 个字符");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "VMS_BOOTSTRAP_ADMIN_PASSWORD 至少需要 " + MIN_PASSWORD_LENGTH + " 个字符");
        }
    }
}
