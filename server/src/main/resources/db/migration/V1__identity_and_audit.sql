CREATE TABLE sys_user (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)     NOT NULL,
    password_hash VARCHAR(100)    NOT NULL,
    role_code     VARCHAR(32)     NOT NULL,
    status        VARCHAR(16)     NOT NULL DEFAULT 'ENABLED',
    auth_version  INT             NOT NULL DEFAULT 1,
    created_at    DATETIME(3)     NOT NULL,
    updated_at    DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username),
    CONSTRAINT ck_sys_user_role_code CHECK (role_code IN ('ADMIN', 'MONITOR', 'FLEET_LEADER')),
    CONSTRAINT ck_sys_user_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '系统账号';

CREATE TABLE audit_log (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    actor_id   BIGINT UNSIGNED NULL,
    actor_name VARCHAR(64)     NULL,
    category   VARCHAR(32)     NOT NULL,
    action     VARCHAR(64)     NOT NULL,
    target     VARCHAR(128)    NULL,
    result     VARCHAR(16)     NOT NULL,
    source_ip  VARCHAR(64)     NULL,
    detail     VARCHAR(1000)   NULL,
    created_at DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_log_created_at (created_at),
    KEY idx_audit_log_actor (actor_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '操作、登录与系统审计';
