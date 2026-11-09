CREATE TABLE fleet (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    fleet_code VARCHAR(64)     NOT NULL,
    fleet_name VARCHAR(128)    NOT NULL,
    status     VARCHAR(16)     NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME(3)     NOT NULL,
    updated_at DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fleet_code (fleet_code),
    CONSTRAINT ck_fleet_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '车队';

CREATE TABLE sys_user_fleet (
    user_id    BIGINT UNSIGNED NOT NULL,
    fleet_id   BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3)     NOT NULL,
    PRIMARY KEY (user_id, fleet_id),
    KEY idx_sys_user_fleet_fleet (fleet_id),
    CONSTRAINT fk_sys_user_fleet_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_sys_user_fleet_fleet FOREIGN KEY (fleet_id) REFERENCES fleet (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '车队长车队授权';
