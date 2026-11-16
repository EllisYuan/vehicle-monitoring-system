package com.vms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.vms.support.IntegrationContainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest(properties = {
        "vms.bootstrap-admin.username=bootstrap-admin",
        "vms.bootstrap-admin.password=Bootstrap-Pass-2026",
})
@Import(IntegrationContainers.class)
class SchemaUpgradeTest {

    @Autowired
    MySQLContainer<?> mysql;

    @Test
    void migrationsApplyOnceOnEmptySchemaAndRepeatSafely() throws Exception {
        String schema = "vms_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
            statement.execute("GRANT ALL ON " + schema + ".* TO '" + mysql.getUsername() + "'@'%'");
        }
        String url = mysql.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + schema + "$1");

        Flyway flyway = Flyway.configure().dataSource(url, mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(12);
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        JdbcTemplate jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                url, mysql.getUsername(), mysql.getPassword()));
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name",
                String.class, schema);
        assertThat(tables).containsExactly("alarm", "alarm_candidate_state", "alarm_handle_log", "alarm_rule", "audit_log",
                "command_log", "driver", "fleet", "flyway_schema_history",
                "geofence", "geofence_vehicle", "geofence_vehicle_state", "notification_outbox", "sys_user", "sys_user_fleet", "system_config", "telemetry_anomaly", "terminal",
                "terminal_command_sequence", "terminal_vehicle_binding", "track_partition_alert",
                "track_partition_maintenance", "track_point", "track_retention_change", "track_retention_rejection",
                "vehicle", "vehicle_driver_binding", "vehicle_fleet_history", "vehicle_last_position",
                "vehicle_monitor_state");
        Map<String, Object> history = jdbc.queryForMap(
                "SELECT count(*) AS applied, count(DISTINCT version) AS versions, min(success) AS all_ok "
                        + "FROM " + schema + ".flyway_schema_history");
        assertThat(((Number) history.get("applied")).longValue()).isEqualTo(12L);
        assertThat(((Number) history.get("versions")).longValue()).isEqualTo(12L);
        assertThat(((Number) history.get("all_ok")).intValue()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".sys_user", Long.class)).isZero();
    }

    @Test
    void migrationUpgradesExistingPartitionedSchemaAndKeepsTrackData() throws Exception {
        String schema = "vms_upgrade_data_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
            statement.execute("GRANT ALL ON " + schema + ".* TO '" + mysql.getUsername() + "'@'%'");
        }
        String url = mysql.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + schema + "$1");

        Flyway partial = Flyway.configure().dataSource(url, mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("6")).load();
        assertThat(partial.migrate().migrationsExecuted).isEqualTo(6);

        JdbcTemplate jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                url, mysql.getUsername(), mysql.getPassword()));
        List<String> partitionsBefore = jdbc.queryForList(
                "SELECT partition_name FROM information_schema.partitions WHERE table_schema = ?"
                        + " AND table_name = 'track_point' AND partition_name IS NOT NULL"
                        + " ORDER BY partition_ordinal_position",
                String.class, schema);
        assertThat(partitionsBefore).hasSize(37);
        LocalDate retainedDay = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        jdbc.update("""
                INSERT INTO track_point (terminal_id, vehicle_id, fleet_id_at_event, gps_time, received_at,
                    lng, lat, speed, direction, alarm_flag, status_flag, locate_state, report_type,
                    payload_hash, raw_body)
                VALUES (1, 1, 1, ?, ?, 116.480000, 39.908000, 10.0, 90, 0, 3, 'VALID', 'REALTIME', ?, ?)
                """, retainedDay.atTime(10, 0), retainedDay.atTime(10, 0, 1),
                "a".repeat(64), new byte[] {1, 2, 3});

        Flyway latest = Flyway.configure().dataSource(url, mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(6);

        Map<String, Object> point = jdbc.queryForMap(
                "SELECT * FROM " + schema + ".track_point WHERE terminal_id = 1");
        assertThat(((Number) point.get("vehicle_id")).longValue()).isEqualTo(1L);
        assertThat(point.get("payload_hash")).isEqualTo("a".repeat(64));
        assertThat(jdbc.queryForList(
                "SELECT partition_name FROM information_schema.partitions WHERE table_schema = ?"
                        + " AND table_name = 'track_point' AND partition_name IS NOT NULL"
                        + " ORDER BY partition_ordinal_position",
                String.class, schema)).isEqualTo(partitionsBefore);
        assertThat(jdbc.queryForObject("SELECT config_value FROM " + schema
                + ".system_config WHERE config_key = 'track.retention.days'", String.class)).isEqualTo("30");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".track_partition_maintenance",
                Long.class)).isZero();
    }
}

