CREATE TABLE driver (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    driver_no VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(50) NOT NULL,
    phone VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fleet_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_driver_no (driver_no),
    KEY idx_driver_fleet (fleet_id),
    CONSTRAINT fk_driver_fleet FOREIGN KEY (fleet_id) REFERENCES fleet(id),
    CONSTRAINT ck_driver_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_driver_phone CHECK (phone REGEXP '^[0-9]{11}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE vehicle_driver_binding (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    driver_id BIGINT UNSIGNED NOT NULL,
    vehicle_id BIGINT UNSIGNED NOT NULL,
    effective_from DATETIME(3) NOT NULL,
    effective_to DATETIME(3) NULL,
    current_vehicle_id BIGINT UNSIGNED GENERATED ALWAYS AS
        (CASE WHEN effective_to IS NULL THEN vehicle_id ELSE NULL END) STORED,
    current_driver_id BIGINT UNSIGNED GENERATED ALWAYS AS
        (CASE WHEN effective_to IS NULL THEN driver_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_driver_binding_current_vehicle (current_vehicle_id),
    UNIQUE KEY uk_driver_binding_current_driver (current_driver_id),
    KEY idx_driver_binding_driver_time (driver_id, effective_from, effective_to),
    KEY idx_driver_binding_vehicle_time (vehicle_id, effective_from, effective_to),
    CONSTRAINT fk_driver_binding_driver FOREIGN KEY (driver_id) REFERENCES driver(id),
    CONSTRAINT fk_driver_binding_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
    CONSTRAINT ck_driver_binding_interval CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
