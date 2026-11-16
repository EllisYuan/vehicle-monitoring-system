-- 12: 全局默认与车队覆盖两级超速规则、持续报警与持久候选判定状态
CREATE TABLE alarm_rule (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fleet_id BIGINT UNSIGNED NULL,
    alarm_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parameters JSON NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    version BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    global_alarm_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS
        (CASE WHEN scope_type = 'GLOBAL' THEN alarm_type ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_alarm_rule_global (global_alarm_type),
    UNIQUE KEY uk_alarm_rule_fleet (fleet_id, alarm_type),
    KEY idx_alarm_rule_fleet (fleet_id),
    CONSTRAINT fk_alarm_rule_fleet FOREIGN KEY (fleet_id) REFERENCES fleet(id),
    CONSTRAINT ck_alarm_rule_scope CHECK
        ((scope_type = 'GLOBAL' AND fleet_id IS NULL) OR (scope_type = 'FLEET' AND fleet_id IS NOT NULL)),
    CONSTRAINT ck_alarm_rule_type CHECK (alarm_type IN ('OVERSPEED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO alarm_rule (scope_type, fleet_id, alarm_type, parameters, enabled, version, created_at, updated_at)
VALUES ('GLOBAL', NULL, 'OVERSPEED', JSON_OBJECT('thresholdKph', 80), 1, 1, NOW(3), NOW(3));

CREATE TABLE alarm (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    vehicle_id BIGINT UNSIGNED NOT NULL,
    terminal_id BIGINT UNSIGNED NOT NULL,
    driver_id BIGINT UNSIGNED NULL,
    fleet_id_at_event BIGINT UNSIGNED NOT NULL,
    source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    alarm_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_dimension VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
    condition_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    handle_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    rule_id BIGINT UNSIGNED NULL,
    rule_version BIGINT UNSIGNED NULL,
    rule_snapshot JSON NULL,
    start_time DATETIME(3) NOT NULL,
    last_time DATETIME(3) NOT NULL,
    recovered_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    active_dimension VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS
        (CASE WHEN condition_state = 'ACTIVE'
            THEN CONCAT(vehicle_id, ':', alarm_type, ':', source, ':', business_dimension)
            ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_alarm_active_dimension (active_dimension),
    KEY idx_alarm_vehicle_start (vehicle_id, start_time),
    KEY idx_alarm_fleet_start (fleet_id_at_event, start_time),
    KEY idx_alarm_state_start (condition_state, start_time),
    CONSTRAINT fk_alarm_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
    CONSTRAINT fk_alarm_terminal FOREIGN KEY (terminal_id) REFERENCES terminal(id),
    CONSTRAINT fk_alarm_driver FOREIGN KEY (driver_id) REFERENCES driver(id),
    CONSTRAINT fk_alarm_fleet FOREIGN KEY (fleet_id_at_event) REFERENCES fleet(id),
    CONSTRAINT fk_alarm_rule FOREIGN KEY (rule_id) REFERENCES alarm_rule(id),
    CONSTRAINT ck_alarm_source CHECK (source IN ('PLATFORM','TERMINAL')),
    CONSTRAINT ck_alarm_type CHECK (alarm_type IN ('OVERSPEED')),
    CONSTRAINT ck_alarm_condition CHECK (condition_state IN ('ACTIVE','RECOVERED','EVENT')),
    CONSTRAINT ck_alarm_handle CHECK (handle_status IN ('PENDING','PROCESSING','HANDLED','IGNORED')),
    CONSTRAINT ck_alarm_recovered CHECK
        ((condition_state = 'RECOVERED' AND recovered_at IS NOT NULL)
            OR (condition_state <> 'RECOVERED' AND recovered_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE alarm_candidate_state (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    vehicle_id BIGINT UNSIGNED NOT NULL,
    alarm_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_dimension VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
    rule_id BIGINT UNSIGNED NOT NULL,
    rule_version BIGINT UNSIGNED NOT NULL,
    candidate_direction VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    consecutive_count INT UNSIGNED NOT NULL,
    last_point_time DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_candidate_dimension (vehicle_id, alarm_type, source, business_dimension),
    CONSTRAINT fk_candidate_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
    CONSTRAINT fk_candidate_rule FOREIGN KEY (rule_id) REFERENCES alarm_rule(id),
    CONSTRAINT ck_candidate_direction CHECK (candidate_direction IN ('OVER','UNDER')),
    CONSTRAINT ck_candidate_count CHECK (consecutive_count >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
