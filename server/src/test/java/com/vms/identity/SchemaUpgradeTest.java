package com.vms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.vms.support.IntegrationContainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
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
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6);
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        JdbcTemplate jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                url, mysql.getUsername(), mysql.getPassword()));
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name",
                String.class, schema);
        assertThat(tables).containsExactly("audit_log", "driver", "fleet", "flyway_schema_history", "notification_outbox", "sys_user",
                "sys_user_fleet", "terminal", "terminal_vehicle_binding", "track_point", "vehicle", "vehicle_driver_binding",
                "vehicle_fleet_history", "vehicle_last_position", "vehicle_monitor_state");

        Map<String, Object> history = jdbc.queryForMap(
                "SELECT count(*) AS applied, count(DISTINCT version) AS versions, min(success) AS all_ok "
                        + "FROM " + schema + ".flyway_schema_history");
        assertThat(((Number) history.get("applied")).longValue()).isEqualTo(6L);
        assertThat(((Number) history.get("versions")).longValue()).isEqualTo(6L);
        assertThat(((Number) history.get("all_ok")).intValue()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".sys_user", Long.class)).isZero();
    }
}
