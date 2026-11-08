package com.vms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vms.audit.AuditService;
import com.vms.identity.bootstrap.AdminBootstrapper;
import com.vms.identity.bootstrap.BootstrapAdminProperties;
import com.vms.identity.security.PasswordHasher;
import com.vms.support.IntegrationContainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest(properties = {
        "vms.bootstrap-admin.username=bootstrap-admin",
        "vms.bootstrap-admin.password=Bootstrap-Pass-2026",
})
@Import(IntegrationContainers.class)
class BootstrapAdminTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AdminBootstrapper bootstrapper;
    @Autowired
    AuditService auditService;
    @Autowired
    Clock clock;
    @Autowired
    MySQLContainer<?> mysql;

    @Test
    void rerunningBootstrapKeepsSingleAdminAndDoesNotOverwritePassword() {
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT id, password_hash FROM sys_user WHERE username = 'bootstrap-admin'");

        bootstrapper.run(null);

        Map<String, Object> after = jdbc.queryForMap(
                "SELECT id, password_hash FROM sys_user WHERE username = 'bootstrap-admin'");
        assertThat(after.get("password_hash")).isEqualTo(before.get("password_hash"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_user", Long.class)).isEqualTo(1L);
    }

    @Test
    void changedConfiguredPasswordNeverRewritesExistingAdmin() {
        PasswordHasher hasher = new PasswordHasher();
        new AdminBootstrapper(jdbc, new BootstrapAdminProperties("bootstrap-admin", "Another-Pass-2027"),
                hasher, auditService, clock).run(null);

        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM sys_user WHERE username = 'bootstrap-admin'", String.class);
        assertThat(hasher.matches("Another-Pass-2027", hash)).isFalse();
        assertThat(hasher.matches("Bootstrap-Pass-2026", hash)).isTrue();
    }

    @Test
    void missingConfigurationFailsOnEmptyDatabaseButNotOnLaterRestarts() {
        JdbcTemplate emptyDatabase = freshDatabase();
        AdminBootstrapper bootstrapperOnEmpty = new AdminBootstrapper(
                emptyDatabase, new BootstrapAdminProperties("", ""), new PasswordHasher(),
                new AuditService(emptyDatabase, clock), clock);

        assertThatThrownBy(() -> bootstrapperOnEmpty.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VMS_BOOTSTRAP_ADMIN_USERNAME");

        AdminBootstrapper configured = new AdminBootstrapper(
                emptyDatabase,
                new BootstrapAdminProperties("bootstrap-admin", "Bootstrap-Pass-2026"),
                new PasswordHasher(), new AuditService(emptyDatabase, clock), clock);
        configured.run(null);

        AdminBootstrapper laterRestart = new AdminBootstrapper(
                emptyDatabase, new BootstrapAdminProperties("", ""), new PasswordHasher(),
                new AuditService(emptyDatabase, clock), clock);
        laterRestart.run(null);

        assertThat(emptyDatabase.queryForObject("SELECT count(*) FROM sys_user", Long.class)).isEqualTo(1L);
    }

    private JdbcTemplate freshDatabase() {
        String schema = "vms_bootstrap_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(
                mysql.getJdbcUrl(), "root", mysql.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
            statement.execute("GRANT ALL ON " + schema + ".* TO '" + mysql.getUsername() + "'@'%'");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String url = mysql.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + schema + "$1");
        Flyway.configure().dataSource(url, mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration").load().migrate();
        return new JdbcTemplate(new DriverManagerDataSource(url, mysql.getUsername(), mysql.getPassword()));
    }
}
