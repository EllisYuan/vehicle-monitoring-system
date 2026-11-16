CREATE TABLE command_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    command_type VARCHAR(16) NOT NULL,
    message_id INT UNSIGNED NOT NULL,
    terminal_id BIGINT UNSIGNED NOT NULL,
    vehicle_id BIGINT UNSIGNED NULL,
    serial_no INT UNSIGNED NOT NULL,
    connection_epoch BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL,
    failure_reason VARCHAR(32) NULL,
    text_content VARCHAR(200) NULL,
    text_type TINYINT UNSIGNED NULL,
    interval_seconds INT UNSIGNED NULL,
    valid_seconds INT UNSIGNED NULL,
    reply_message_id INT UNSIGNED NULL,
    reply_result INT UNSIGNED NULL,
    reply_time DATETIME(3) NULL,
    operator_id BIGINT UNSIGNED NOT NULL,
    submitted_at DATETIME(3) NOT NULL,
    send_time DATETIME(3) NULL,
    retry_of_id BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    KEY idx_command_log_terminal (terminal_id, id),
    KEY idx_command_log_status (status, send_time),
    CONSTRAINT fk_command_log_terminal FOREIGN KEY (terminal_id) REFERENCES terminal (id),
    CONSTRAINT fk_command_log_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle (id),
    CONSTRAINT fk_command_log_retry FOREIGN KEY (retry_of_id) REFERENCES command_log (id),
    CONSTRAINT ck_command_log_status CHECK (status IN ('SUBMITTED', 'SENT', 'ACK_SUCCESS', 'FAILED', 'TIMEOUT')),
    CONSTRAINT ck_command_log_type CHECK (command_type IN ('TEXT', 'TRACKING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE terminal_command_sequence (
    terminal_id BIGINT UNSIGNED NOT NULL,
    last_serial INT UNSIGNED NOT NULL,
    PRIMARY KEY (terminal_id),
    CONSTRAINT fk_terminal_command_sequence FOREIGN KEY (terminal_id) REFERENCES terminal (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
