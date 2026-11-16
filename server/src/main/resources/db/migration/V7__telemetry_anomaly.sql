-- 08: 定位质量与冲突异常（受限原始内容，仅授权查询）
CREATE TABLE telemetry_anomaly (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    terminal_id BIGINT UNSIGNED NULL,
    vehicle_id BIGINT UNSIGNED NULL,
    fleet_id_at_event BIGINT UNSIGNED NULL,
    gps_time DATETIME NULL,
    received_at DATETIME(3) NOT NULL,
    reason VARCHAR(48) CHARACTER SET ascii NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii NULL,
    body_length INT UNSIGNED NULL,
    raw_body VARBINARY(255) NULL,
    PRIMARY KEY (id),
    KEY idx_anomaly_received (received_at),
    KEY idx_anomaly_vehicle_time (vehicle_id, gps_time),
    KEY idx_anomaly_terminal_time (terminal_id, gps_time)
) ENGINE=InnoDB;
