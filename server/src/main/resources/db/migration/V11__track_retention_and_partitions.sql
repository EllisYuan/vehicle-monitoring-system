-- 轨迹保留配置、分区维护记录、失败告警、缩短保留期预览与受限拒绝摘要。
-- track_point 的初始日分区由 V6 建立；本迁移不改变既有轨迹表结构。
CREATE TABLE system_config (
    config_key   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    config_value VARCHAR(255) NOT NULL,
    version      BIGINT NOT NULL DEFAULT 1,
    updated_by   BIGINT UNSIGNED NULL,
    updated_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统配置';

INSERT INTO system_config (config_key, config_value, version, updated_by, updated_at)
VALUES ('track.retention.days', '30', 1, NULL, CURRENT_TIMESTAMP(3));

CREATE TABLE track_partition_maintenance (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    trigger_type          VARCHAR(16) NOT NULL,
    status                VARCHAR(16) NOT NULL,
    retention_days        INT NOT NULL,
    created_partitions    TEXT NULL,
    dropped_partitions    TEXT NULL,
    dropped_rows_estimate BIGINT UNSIGNED NOT NULL DEFAULT 0,
    message               VARCHAR(1000) NULL,
    started_at            DATETIME(3) NOT NULL,
    finished_at           DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_partition_maintenance_started (started_at),
    CONSTRAINT ck_partition_maintenance_trigger CHECK (trigger_type IN ('STARTUP','DAILY','MANUAL','CONFIRM')),
    CONSTRAINT ck_partition_maintenance_status CHECK (status IN ('SUCCESS','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='轨迹分区维护记录';

CREATE TABLE track_partition_alert (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    alert_type VARCHAR(32) NOT NULL,
    message    VARCHAR(1000) NOT NULL,
    detail     VARCHAR(2000) NULL,
    run_id     BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_partition_alert_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='轨迹分区维护告警';

CREATE TABLE track_retention_change (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    token            CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_days     INT NOT NULL,
    proposed_days    INT NOT NULL,
    current_version  BIGINT NOT NULL,
    cutoff_date      DATE NOT NULL,
    partitions_json  TEXT NOT NULL,
    estimated_rows   BIGINT UNSIGNED NOT NULL DEFAULT 0,
    status           VARCHAR(16) NOT NULL,
    created_by       BIGINT UNSIGNED NULL,
    created_by_name  VARCHAR(64) NULL,
    created_at       DATETIME(3) NOT NULL,
    expires_at       DATETIME(3) NOT NULL,
    confirmed_at     DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_retention_change_token (token),
    KEY idx_retention_change_status (status),
    CONSTRAINT ck_retention_change_status CHECK (status IN ('PREVIEWED','CONFIRMED','EXPIRED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='缩短轨迹保留期预览与确认';

CREATE TABLE track_retention_rejection (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    terminal_id  BIGINT UNSIGNED NOT NULL,
    vehicle_id   BIGINT UNSIGNED NULL,
    gps_time     DATETIME NOT NULL,
    received_at  DATETIME(3) NOT NULL,
    reason       VARCHAR(32) NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    body_length  INT UNSIGNED NOT NULL,
    created_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_retention_rejection_terminal (terminal_id, gps_time),
    KEY idx_retention_rejection_time (gps_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='保留期外位置受限异常摘要';
