-- 13: 报警处置状态迁移、异常条件变化与人工处置分开留痕
CREATE TABLE alarm_handle_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    alarm_id BIGINT UNSIGNED NOT NULL,
    change_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    to_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id BIGINT UNSIGNED NULL,
    remark VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_alarm_handle_log_alarm (alarm_id, created_at, id),
    CONSTRAINT fk_alarm_handle_log_alarm FOREIGN KEY (alarm_id) REFERENCES alarm(id),
    CONSTRAINT fk_alarm_handle_log_operator FOREIGN KEY (operator_id) REFERENCES sys_user(id),
    CONSTRAINT ck_alarm_handle_log_type CHECK (change_type IN ('CONDITION','HANDLE')),
    CONSTRAINT ck_alarm_handle_log_from CHECK
        (from_status IN ('ACTIVE','RECOVERED','PENDING','PROCESSING','HANDLED','IGNORED')),
    CONSTRAINT ck_alarm_handle_log_to CHECK
        (to_status IN ('ACTIVE','RECOVERED','PENDING','PROCESSING','HANDLED','IGNORED')),
    CONSTRAINT ck_alarm_handle_log_change CHECK (from_status <> to_status),
    CONSTRAINT ck_alarm_handle_log_operator CHECK
        ((change_type = 'HANDLE' AND operator_id IS NOT NULL)
            OR (change_type = 'CONDITION' AND operator_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
