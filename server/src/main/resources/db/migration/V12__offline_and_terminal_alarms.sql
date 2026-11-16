-- 14: 离线、定位无效与终端来源报警

-- 合法注销保留最后注销时刻；离线扫描据此排除主动注销，新通信晚于注销时排除自动失效。
ALTER TABLE terminal ADD COLUMN last_logout_at DATETIME(3) NULL AFTER last_seen_at;

-- 停用、解绑等管理操作保留既有 ACTIVE 并标注监控范围变化；不写 recovered_at。
ALTER TABLE alarm ADD COLUMN scope_changed_at DATETIME(3) NULL AFTER recovered_at;

ALTER TABLE alarm DROP CHECK ck_alarm_type;
ALTER TABLE alarm ADD CONSTRAINT ck_alarm_type
    CHECK (alarm_type IN ('OVERSPEED', 'OFFLINE', 'POSITION_INVALID'));

ALTER TABLE alarm_candidate_state
    MODIFY COLUMN rule_id BIGINT UNSIGNED NULL,
    MODIFY COLUMN rule_version BIGINT UNSIGNED NULL;

ALTER TABLE alarm_candidate_state DROP CHECK ck_candidate_direction;
ALTER TABLE alarm_candidate_state ADD CONSTRAINT ck_candidate_direction
    CHECK (candidate_direction IN ('OVER', 'UNDER', 'INVALID', 'VALID'));

CREATE INDEX idx_alarm_terminal_type_state ON alarm (terminal_id, alarm_type, condition_state);