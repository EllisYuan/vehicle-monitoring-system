package db.migration;

import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 首次升级按 Asia/Shanghai 建立今天、此前 29 天及未来 7 天的日分区。 */
public class V6__durable_position extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        LocalDate first = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(29);
        StringBuilder partitions = new StringBuilder();
        for (int i = 0; i < 37; i++) {
            LocalDate day = first.plusDays(i);
            if (i > 0) partitions.append(",");
            partitions.append("PARTITION p").append(day.format(DateTimeFormatter.BASIC_ISO_DATE))
                    .append(" VALUES LESS THAN ('").append(day.plusDays(1)).append("')");
        }
        try (Statement sql = context.getConnection().createStatement()) {
            sql.execute("""
                    CREATE TABLE track_point (
                        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                        terminal_id BIGINT UNSIGNED NOT NULL,
                        vehicle_id BIGINT UNSIGNED NOT NULL,
                        fleet_id_at_event BIGINT UNSIGNED NOT NULL,
                        gps_time DATETIME NOT NULL,
                        received_at DATETIME(3) NOT NULL,
                        lng DECIMAL(10,6) NULL,
                        lat DECIMAL(9,6) NULL,
                        speed DECIMAL(6,1) NOT NULL,
                        direction SMALLINT UNSIGNED NOT NULL,
                        alarm_flag BIGINT UNSIGNED NOT NULL,
                        status_flag BIGINT UNSIGNED NOT NULL,
                        locate_state VARCHAR(16) NOT NULL,
                        report_type VARCHAR(16) NOT NULL,
                        payload_hash CHAR(64) CHARACTER SET ascii NOT NULL,
                        raw_body VARBINARY(1023) NOT NULL,
                        PRIMARY KEY (id, gps_time),
                        UNIQUE KEY uk_track_terminal_time (terminal_id, gps_time),
                        KEY idx_track_vehicle_time (vehicle_id, gps_time)
                    ) ENGINE=InnoDB PARTITION BY RANGE COLUMNS (gps_time) (
                    """ + partitions + ")");
            sql.execute("""
                    CREATE TABLE vehicle_last_position (
                        vehicle_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
                        terminal_id BIGINT UNSIGNED NOT NULL,
                        gps_time DATETIME NOT NULL,
                        received_at DATETIME(3) NOT NULL,
                        lng DECIMAL(10,6) NOT NULL,
                        lat DECIMAL(9,6) NOT NULL,
                        speed DECIMAL(6,1) NOT NULL,
                        direction SMALLINT UNSIGNED NOT NULL,
                        status_flag BIGINT UNSIGNED NOT NULL,
                        version BIGINT NOT NULL,
                        CONSTRAINT fk_last_position_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id)
                    ) ENGINE=InnoDB
                    """);
            sql.execute("""
                    CREATE TABLE vehicle_monitor_state (
                        vehicle_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
                        gps_time DATETIME NOT NULL,
                        version BIGINT NOT NULL,
                        CONSTRAINT fk_monitor_state_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id)
                    ) ENGINE=InnoDB
                    """);
            sql.execute("""
                    CREATE TABLE notification_outbox (
                        event_id VARCHAR(80) CHARACTER SET ascii NOT NULL PRIMARY KEY,
                        event_type VARCHAR(32) NOT NULL,
                        aggregate_id BIGINT UNSIGNED NOT NULL,
                        version BIGINT NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        retry_at DATETIME(3) NOT NULL,
                        attempts INT UNSIGNED NOT NULL DEFAULT 0,
                        updated_at DATETIME(3) NOT NULL,
                        KEY idx_outbox_pending (status, retry_at),
                        CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING','RETRY','DONE','DEAD'))
                    ) ENGINE=InnoDB
                    """);
        }
    }
}
