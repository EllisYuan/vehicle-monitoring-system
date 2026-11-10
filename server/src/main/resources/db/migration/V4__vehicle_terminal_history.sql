CREATE TABLE vehicle (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    plate_no VARCHAR(20) NOT NULL,
    fleet_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vehicle_plate_no (plate_no),
    KEY idx_vehicle_fleet (fleet_id),
    CONSTRAINT fk_vehicle_fleet FOREIGN KEY (fleet_id) REFERENCES fleet(id),
    CONSTRAINT ck_vehicle_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE terminal (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    terminal_no VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    auth_secret_hash VARCHAR(100) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    last_seen_at DATETIME(3) NULL,
    ever_authenticated BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_terminal_no (terminal_no),
    CONSTRAINT ck_terminal_no CHECK (terminal_no REGEXP '^[0-9]{20}$'),
    CONSTRAINT ck_terminal_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE terminal_vehicle_binding (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    terminal_id BIGINT UNSIGNED NOT NULL,
    vehicle_id BIGINT UNSIGNED NOT NULL,
    effective_from DATETIME(3) NOT NULL,
    effective_to DATETIME(3) NULL,
    current_terminal_id BIGINT UNSIGNED GENERATED ALWAYS AS
        (CASE WHEN effective_to IS NULL THEN terminal_id ELSE NULL END) STORED,
    current_vehicle_id BIGINT UNSIGNED GENERATED ALWAYS AS
        (CASE WHEN effective_to IS NULL THEN vehicle_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_binding_current_terminal (current_terminal_id),
    UNIQUE KEY uk_binding_current_vehicle (current_vehicle_id),
    KEY idx_binding_terminal_time (terminal_id, effective_from, effective_to),
    KEY idx_binding_vehicle_time (vehicle_id, effective_from, effective_to),
    CONSTRAINT fk_binding_terminal FOREIGN KEY (terminal_id) REFERENCES terminal(id),
    CONSTRAINT fk_binding_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
    CONSTRAINT ck_binding_interval CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE vehicle_fleet_history (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    vehicle_id BIGINT UNSIGNED NOT NULL,
    fleet_id BIGINT UNSIGNED NOT NULL,
    effective_from DATETIME(3) NOT NULL,
    effective_to DATETIME(3) NULL,
    current_vehicle_id BIGINT UNSIGNED GENERATED ALWAYS AS
        (CASE WHEN effective_to IS NULL THEN vehicle_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fleet_history_current_vehicle (current_vehicle_id),
    KEY idx_fleet_history_vehicle_time (vehicle_id, effective_from, effective_to),
    CONSTRAINT fk_fleet_history_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
    CONSTRAINT fk_fleet_history_fleet FOREIGN KEY (fleet_id) REFERENCES fleet(id),
    CONSTRAINT ck_fleet_history_interval CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
