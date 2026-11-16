-- 15: 圆/多边形围栏、车辆绑定与持久判定基线；几何按 WGS84（SRID 4326）保存
CREATE TABLE geofence (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    fence_name VARCHAR(64) NOT NULL,
    fence_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    center_lng DECIMAL(10,7) NULL,
    center_lat DECIMAL(10,7) NULL,
    radius DOUBLE NULL,
    vertices JSON NULL,
    center_point POINT SRID 4326 NULL,
    boundary POLYGON SRID 4326 NULL,
    enter_enabled TINYINT(1) NOT NULL DEFAULT 1,
    leave_enabled TINYINT(1) NOT NULL DEFAULT 1,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ENABLED',
    version BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_geofence_name (fence_name),
    KEY idx_geofence_status (status),
    CONSTRAINT ck_geofence_type CHECK (fence_type IN ('CIRCLE','POLYGON')),
    CONSTRAINT ck_geofence_status CHECK (status IN ('ENABLED','DISABLED')),
    CONSTRAINT ck_geofence_center CHECK (
        (center_lng IS NULL OR center_lng BETWEEN -180 AND 180)
        AND (center_lat IS NULL OR center_lat BETWEEN -90 AND 90)),
    CONSTRAINT ck_geofence_radius CHECK (radius IS NULL OR radius BETWEEN 10 AND 100000),
    CONSTRAINT ck_geofence_shape CHECK (
        (fence_type = 'CIRCLE' AND center_lng IS NOT NULL AND center_lat IS NOT NULL
            AND radius IS NOT NULL AND vertices IS NULL)
        OR (fence_type = 'POLYGON' AND center_lng IS NULL AND center_lat IS NULL
            AND radius IS NULL AND vertices IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE geofence_vehicle (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    geofence_id BIGINT UNSIGNED NOT NULL,
    vehicle_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_geofence_vehicle (geofence_id, vehicle_id),
    KEY idx_geofence_vehicle_vehicle (vehicle_id),
    CONSTRAINT fk_geofence_vehicle_fence FOREIGN KEY (geofence_id) REFERENCES geofence(id),
    CONSTRAINT fk_geofence_vehicle_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 每车每围栏一条稳定内外基线；fence_version 与 geofence.version 不一致时按首个实时点重建基线。
CREATE TABLE geofence_vehicle_state (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    geofence_id BIGINT UNSIGNED NOT NULL,
    vehicle_id BIGINT UNSIGNED NOT NULL,
    fence_version BIGINT UNSIGNED NOT NULL,
    inside_state VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_state VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL,
    consecutive_count INT UNSIGNED NOT NULL DEFAULT 0,
    last_point_time DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_geofence_state (geofence_id, vehicle_id),
    KEY idx_geofence_state_vehicle (vehicle_id),
    CONSTRAINT fk_geofence_state_fence FOREIGN KEY (geofence_id) REFERENCES geofence(id),
    CONSTRAINT fk_geofence_state_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
    CONSTRAINT ck_geofence_state_inside CHECK (inside_state IN ('INSIDE','OUTSIDE')),
    CONSTRAINT ck_geofence_state_candidate CHECK
        (candidate_state IS NULL OR candidate_state IN ('INSIDE','OUTSIDE')),
    CONSTRAINT ck_geofence_state_count CHECK (consecutive_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 围栏进出为瞬时事件，沿用 12 的 alarm 结构与 ALARM_EVENT outbox；扩展允许的事件类型。
ALTER TABLE alarm DROP CHECK ck_alarm_type;
ALTER TABLE alarm ADD CONSTRAINT ck_alarm_type CHECK
    (alarm_type IN ('OVERSPEED','GEOFENCE_ENTER','GEOFENCE_LEAVE'));