package com.vms.track;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.vms.access.TerminalAccessPort;
import com.vms.infrastructure.ClockConfig;
import com.vms.protocol.BodyProperties;
import com.vms.protocol.Frame;
import com.vms.protocol.FrameCodec;
import com.vms.protocol.MessageHeader;
import com.vms.protocol.PositionReport;
import com.vms.protocol.TerminalAuthentication;
import com.vms.protocol.TerminalGeneralResponse;
import com.vms.protocol.TerminalRegister;
import com.vms.protocol.TerminalRegisterResponse;
import com.vms.support.IntegrationContainers;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "vms.bootstrap-admin.username=retention-admin",
        "vms.bootstrap-admin.password=Retention-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0",
        "vms.track.daily-cron=0 0 3 1 1 *"
})
@Import(IntegrationContainers.class)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrackRetentionMaintenanceTest {

    static final String ADMIN_NAME = "retention-admin";
    static final String ADMIN_PASSWORD = "Retention-Pass-2026";
    static final DateTimeFormatter PARTITION_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TrackPartitionMaintenance maintenance;
    @Autowired TrackRetentionPolicy policy;
    @Autowired TerminalAccessPort access;

    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);

    @Test
    @Order(1)
    void startupMaintenanceEnsuresRetentionAndFuturePartitionsAndRecordsResult() {
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT * FROM track_partition_maintenance WHERE trigger_type='STARTUP' ORDER BY id LIMIT 1");
        assertThat(run.get("status")).isEqualTo("SUCCESS");
        assertThat(((Number) run.get("retention_days")).intValue()).isEqualTo(30);
        assertThat(run.get("finished_at")).isNotNull();

        LocalDate today = LocalDate.now(ClockConfig.BUSINESS_ZONE);
        List<String> partitions = partitionNames();
        for (int daysAgo = 29; daysAgo >= 0; daysAgo--) {
            assertThat(partitions).as("retained day " + daysAgo).contains(partitionName(today.minusDays(daysAgo)));
        }
        for (int daysAhead = 1; daysAhead <= 7; daysAhead++) {
            assertThat(partitions).as("prebuilt future day " + daysAhead)
                    .contains(partitionName(today.plusDays(daysAhead)));
        }
    }

    @Test
    @Order(2)
    void expiredPartitionsOutsideRetentionAreDroppedAndRangeIsRecorded() {
        LocalDate today = LocalDate.now(ClockConfig.BUSINESS_ZONE);
        LocalDate firstRetained = today.minusDays(29);
        LocalDate expired = today.minusDays(40);
        jdbc.execute("ALTER TABLE track_point REORGANIZE PARTITION " + partitionName(firstRetained)
                + " INTO (PARTITION " + partitionName(expired) + " VALUES LESS THAN ('" + expired.plusDays(1)
                + "'), PARTITION " + partitionName(firstRetained) + " VALUES LESS THAN ('"
                + firstRetained.plusDays(1) + "'))");
        assertThat(partitionNames()).contains(partitionName(expired));

        TrackPartitionMaintenance.MaintenanceResult result = maintenance.run("MANUAL");

        assertThat(result.success()).isTrue();
        assertThat(result.droppedPartitions()).contains(partitionName(expired));
        assertThat(partitionNames()).doesNotContain(partitionName(expired));
        assertThat(partitionNames()).as("first retained day is kept").contains(partitionName(firstRetained));
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT * FROM track_partition_maintenance WHERE id = ?", result.runId());
        assertThat(run.get("status")).isEqualTo("SUCCESS");
        assertThat((String) run.get("dropped_partitions")).contains(partitionName(expired));
        assertThat(run.get("finished_at")).isNotNull();
    }

    @Test
    @Order(3)
    void failedMaintenanceIsRecordedAsAlertWithVisibleReason() {
        jdbc.execute("RENAME TABLE track_point TO track_point_unavailable");
        try {
            TrackPartitionMaintenance.MaintenanceResult result = maintenance.run("MANUAL");

            assertThat(result.success()).isFalse();
            assertThat(result.message()).isNotBlank();
            Map<String, Object> run = jdbc.queryForMap(
                    "SELECT * FROM track_partition_maintenance WHERE id = ?", result.runId());
            assertThat(run.get("status")).isEqualTo("FAILED");
            assertThat((String) run.get("message")).contains("track_point");
            Map<String, Object> alert = jdbc.queryForMap(
                    "SELECT * FROM track_partition_alert WHERE run_id = ?", result.runId());
            assertThat(alert.get("alert_type")).isEqualTo("PARTITION_MAINTENANCE_FAILED");
            assertThat((String) alert.get("message")).isNotBlank();
            assertThat(alert.get("created_at")).isNotNull();
        } finally {
            jdbc.execute("RENAME TABLE track_point_unavailable TO track_point");
        }
    }

    @Test
    @Order(4)
    void retentionShorteningPreviewListsDroppedRangeWithoutChangingConfig() throws Exception {
        LocalDate today = LocalDate.now(ClockConfig.BUSINESS_ZONE);
        String token = login();

        JsonNode preview = json.readTree(mvc.perform(post("/api/v1/admin/track-retention/preview")
                        .header("satoken", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("retentionDays", 10))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsByteArray());

        assertThat(preview.path("currentDays").asInt()).isEqualTo(30);
        assertThat(preview.path("proposedDays").asInt()).isEqualTo(10);
        assertThat(preview.path("cutoffDate").asText()).isEqualTo(today.minusDays(9).toString());
        List<String> previewedPartitions = new ArrayList<>();
        preview.path("partitions").forEach(node -> previewedPartitions.add(node.asText()));
        assertThat(previewedPartitions)
                .contains(partitionName(today.minusDays(20)), partitionName(today.minusDays(29)))
                .doesNotContain(partitionName(today.minusDays(9)));
        assertThat(preview.path("token").asText()).isNotBlank();

        assertThat(partitionNames()).as("preview must not drop partitions")
                .contains(partitionName(today.minusDays(20)));
        assertThat(jdbc.queryForObject(
                "SELECT config_value FROM system_config WHERE config_key = 'track.retention.days'", String.class))
                .isEqualTo("30");
        Map<String, Object> change = jdbc.queryForMap(
                "SELECT * FROM track_retention_change WHERE token = ?", preview.path("token").asText());
        assertThat(change.get("status")).isEqualTo("PREVIEWED");
        assertThat(change.get("current_days")).isEqualTo(30);
        assertThat(change.get("proposed_days")).isEqualTo(10);
        assertThat(change.get("created_by_name")).isEqualTo(ADMIN_NAME);
    }

    @Test
    @Order(5)
    void confirmedShorteningDropsExpiredPartitionsAndAuditsOperatorOldNewAndResult() throws Exception {
        LocalDate today = LocalDate.now(ClockConfig.BUSINESS_ZONE);
        String token = login();
        JsonNode preview = json.readTree(mvc.perform(post("/api/v1/admin/track-retention/preview")
                        .header("satoken", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("retentionDays", 10))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsByteArray());

        JsonNode confirmed = json.readTree(mvc.perform(post("/api/v1/admin/track-retention/confirm")
                        .header("satoken", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", preview.path("token").asText()))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsByteArray());

        assertThat(confirmed.path("previousDays").asInt()).isEqualTo(30);
        assertThat(confirmed.path("retentionDays").asInt()).isEqualTo(10);
        assertThat(confirmed.path("success").asBoolean()).isTrue();
        List<String> dropped = new ArrayList<>();
        confirmed.path("droppedPartitions").forEach(node -> dropped.add(node.asText()));
        assertThat(dropped).contains(partitionName(today.minusDays(20)), partitionName(today.minusDays(29)));
        assertThat(partitionNames())
                .doesNotContain(partitionName(today.minusDays(20)))
                .contains(partitionName(today.minusDays(9)), partitionName(today));

        Map<String, Object> config = jdbc.queryForMap(
                "SELECT * FROM system_config WHERE config_key = 'track.retention.days'");
        assertThat(config.get("config_value")).isEqualTo("10");
        assertThat(((Number) config.get("version")).longValue()).isEqualTo(2L);
        assertThat(config.get("updated_by")).isNotNull();
        assertThat(policy.retentionDays()).isEqualTo(10);

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT * FROM audit_log WHERE category = 'TRACK_RETENTION'
                ORDER BY id DESC LIMIT 1
                """);
        assertThat(audit.get("actor_name")).isEqualTo(ADMIN_NAME);
        assertThat(audit.get("actor_id")).isNotNull();
        assertThat(audit.get("result")).isEqualTo("SUCCESS");
        assertThat(audit.get("target")).isEqualTo("track.retention.days");
        assertThat((String) audit.get("detail")).contains("30 -> 10");

        Map<String, Object> change = jdbc.queryForMap(
                "SELECT * FROM track_retention_change WHERE token = ?", preview.path("token").asText());
        assertThat(change.get("status")).isEqualTo("CONFIRMED");
        assertThat(change.get("confirmed_at")).isNotNull();
    }

    @Test
    @Order(6)
    void outOfRetentionPositionRecordsRestrictedRejectionWithoutRebuildingPartition() throws Exception {
        LocalDate today = LocalDate.now(ClockConfig.BUSINESS_ZONE);
        LocalDate outside = today.minusDays(31);
        String token = login();
        BoundTerminal bound = boundTerminal(token);
        LocalDateTime gpsTime = outside.atTime(10, 0);
        try (TerminalClient terminal = authenticated(bound)) {
            terminal.send(0x0200, 3, position(gpsTime).encode());
            Frame ack = terminal.receive();
            assertThat(TerminalGeneralResponse.parse(ack.body()).result()).isZero();
        }

        Map<String, Object> rejection = jdbc.queryForMap("""
                SELECT * FROM track_retention_rejection WHERE terminal_id = ?
                ORDER BY id DESC LIMIT 1
                """, bound.terminalId());
        assertThat(rejection.get("reason")).isEqualTo("OUTSIDE_RETENTION");
        assertThat(((Number) rejection.get("vehicle_id")).longValue()).isEqualTo(bound.vehicleId());
        assertThat((String) rejection.get("payload_hash")).hasSize(64);
        assertThat(((Number) rejection.get("body_length")).intValue()).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM track_point WHERE terminal_id = ?", Long.class, bound.terminalId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM vehicle_last_position WHERE vehicle_id = ?", Long.class, bound.vehicleId()))
                .isZero();
        assertThat(partitionNames()).doesNotContain(partitionName(outside));
    }

    @Test
    @Order(7)
    void dailyInspectionRecordsResultAndRepeatedStartupStaysIdempotent() {
        LocalDate today = LocalDate.now(ClockConfig.BUSINESS_ZONE);
        TrackPartitionMaintenance.MaintenanceResult repeatedStartup = maintenance.run("STARTUP");
        assertThat(repeatedStartup.success()).isTrue();
        assertThat(repeatedStartup.createdPartitions()).as("restart must not duplicate partitions").isEmpty();

        maintenance.dailyInspection();

        Map<String, Object> run = jdbc.queryForMap("""
                SELECT * FROM track_partition_maintenance WHERE trigger_type = 'DAILY'
                ORDER BY id DESC LIMIT 1
                """);
        assertThat(run.get("status")).isEqualTo("SUCCESS");
        assertThat(run.get("finished_at")).isNotNull();
        assertThat(((Number) run.get("retention_days")).intValue()).isEqualTo(10);
        List<String> partitions = partitionNames();
        for (int daysAhead = 1; daysAhead <= 7; daysAhead++) {
            assertThat(partitions).as("daily inspection prebuilds day " + daysAhead)
                    .contains(partitionName(today.plusDays(daysAhead)));
        }
    }

    @Test
    @Order(8)
    void cleanupKeepsBindingHistoryAuditAndRetentionRecords() {
        long auditBefore = count("audit_log");
        long bindingsBefore = count("terminal_vehicle_binding");
        long fleetHistoryBefore = count("vehicle_fleet_history");
        long changesBefore = count("track_retention_change");
        long runsBefore = count("track_partition_maintenance");
        jdbc.update("""
                INSERT INTO audit_log (actor_name, category, action, target, result, source_ip, detail, created_at)
                VALUES ('retention-admin', 'TEST_PRESERVE', 'cleanup', 'track.retention.days', 'SUCCESS',
                    '127.0.0.1', 'cleanup must not delete audit', ?)
                """, LocalDateTime.now(ClockConfig.BUSINESS_ZONE));

        TrackPartitionMaintenance.MaintenanceResult result = maintenance.run("MANUAL");

        assertThat(result.success()).isTrue();
        assertThat(count("audit_log")).isEqualTo(auditBefore + 1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE category = 'TEST_PRESERVE'", Long.class)).isEqualTo(1L);
        assertThat(count("terminal_vehicle_binding")).isEqualTo(bindingsBefore);
        assertThat(count("vehicle_fleet_history")).isEqualTo(fleetHistoryBefore);
        assertThat(count("sys_user")).isPositive();
        assertThat(count("track_retention_change")).isEqualTo(changesBefore);
        assertThat(count("track_partition_maintenance")).isEqualTo(runsBefore + 1);
    }

    @Test
    @Order(9)
    void maintenanceFillsMissingHistoricalDayInsideRetentionRange() {
        LocalDate today = LocalDate.now(ClockConfig.BUSINESS_ZONE);
        LocalDate missing = today.minusDays(5);
        String missingPartition = partitionName(missing);
        jdbc.execute("ALTER TABLE track_point DROP PARTITION " + missingPartition);
        assertThat(partitionNames()).doesNotContain(missingPartition);

        TrackPartitionMaintenance.MaintenanceResult result = maintenance.run("MANUAL");

        assertThat(result.success()).isTrue();
        assertThat(result.createdPartitions()).contains(missingPartition);
        List<String> partitions = partitionNames();
        assertThat(partitions).contains(missingPartition);
        int index = partitions.indexOf(missingPartition);
        assertThat(partitions.get(index - 1)).isEqualTo(partitionName(missing.minusDays(1)));
        assertThat(partitions.get(index + 1)).isEqualTo(partitionName(missing.plusDays(1)));
    }

    @Test
    @Order(10)
    void failedConfirmKeepsFailureEvidenceAndDoesNotReportSuccess() throws Exception {
        String token = login();
        JsonNode preview = json.readTree(mvc.perform(post("/api/v1/admin/track-retention/preview")
                        .header("satoken", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("retentionDays", 5))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        String changeToken = preview.path("token").asText();
        LocalDate retainedDay = LocalDate.now(ClockConfig.BUSINESS_ZONE).minusDays(5);
        assertThat(partitionNames()).contains(partitionName(retainedDay));

        byte[] problem;
        jdbc.execute("RENAME TABLE track_point TO track_point_unavailable");
        try {
            problem = mvc.perform(post("/api/v1/admin/track-retention/confirm")
                            .header("satoken", token).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("token", changeToken))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                    .andReturn().getResponse().getContentAsByteArray();
        } finally {
            jdbc.execute("RENAME TABLE track_point_unavailable TO track_point");
        }
        assertThat(json.readTree(problem).path("code").asText()).isEqualTo("RETENTION_MAINTENANCE_FAILED");

        Map<String, Object> change = jdbc.queryForMap(
                "SELECT * FROM track_retention_change WHERE token = ?", changeToken);
        assertThat(change.get("status")).isEqualTo("FAILED");
        assertThat(change.get("confirmed_at")).isNull();

        JsonNode overview = json.readTree(mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/admin/track-retention")
                                .header("satoken", token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(overview.path("maintenanceRuns").toString())
                .contains("CONFIRM FAILED").contains("track_point");
        assertThat(overview.path("alerts").toString())
                .contains("PARTITION_MAINTENANCE_FAILED");

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT * FROM audit_log WHERE category = 'TRACK_RETENTION'
                ORDER BY id DESC LIMIT 1
                """);
        assertThat(audit.get("result")).isEqualTo("FAILURE");
        assertThat(audit.get("actor_name")).isEqualTo(ADMIN_NAME);
        assertThat((String) audit.get("detail")).contains("10 -> 5").contains("分区维护失败");

        assertThat(jdbc.queryForObject(
                "SELECT config_value FROM system_config WHERE config_key = 'track.retention.days'", String.class))
                .isEqualTo("5");
        assertThat(partitionNames()).as("failed cleanup must not drop retained-range partitions")
                .contains(partitionName(retainedDay));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private PositionReport position(LocalDateTime time) {
        return new PositionReport(0, 3, 39.908, 116.480, 10, 600, 90, time, List.of());
    }

    private BoundTerminal boundTerminal(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String terminalNo = "013800139000" + String.format("%08d", SEQUENCE.getAndIncrement());
        long fleet = create(token, "/fleets", Map.of("fleetCode", "R" + suffix, "fleetName", "保留验证" + suffix));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", "保留" + suffix, "fleetId", fleet));
        long terminal = create(token, "/terminals", Map.of("terminalNo", terminalNo));
        mvc.perform(put("/api/v1/vehicles/" + vehicle + "/terminal").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("terminalId", terminal))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        return new BoundTerminal(terminal, vehicle, fleet, terminalNo);
    }

    private TerminalClient authenticated(BoundTerminal bound) throws Exception {
        TerminalClient client = new TerminalClient(access.port(), bound.terminalNo());
        client.send(0x0100, 1, new TerminalRegister(1, 1, "ABC12", "TESTMODEL", bound.terminalNo(), 2,
                "保留车辆").encode());
        TerminalRegisterResponse registration = TerminalRegisterResponse.parse(client.receive().body());
        assertThat(registration.result()).isZero();
        client.send(0x0102, 2, new TerminalAuthentication(registration.authCode(), "012345678901234",
                "00000000000000000001").encode());
        assertThat(TerminalGeneralResponse.parse(client.receive().body()).result()).isZero();
        return client;
    }

    private long create(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1" + path).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andReturn().getResponse().getContentAsByteArray()).path("id").asLong();
    }

    private String login() throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", ADMIN_NAME, "password", ADMIN_PASSWORD))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).path("token").asText();
    }

    private List<String> partitionNames() {
        return jdbc.queryForList("""
                SELECT PARTITION_NAME FROM information_schema.PARTITIONS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'track_point' AND PARTITION_NAME IS NOT NULL
                ORDER BY PARTITION_ORDINAL_POSITION
                """, String.class);
    }

    private static String partitionName(LocalDate day) {
        return "p" + day.format(PARTITION_DATE);
    }

    private record BoundTerminal(long terminalId, long vehicleId, long fleetId, String terminalNo) {
    }

    private static final class TerminalClient implements AutoCloseable {
        private final Socket socket = new Socket();
        private final String terminalNo;

        TerminalClient(int port, String terminalNo) throws Exception {
            this.terminalNo = terminalNo;
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(5000);
        }

        void send(int messageId, int serial, byte[] body) throws Exception {
            socket.getOutputStream().write(FrameCodec.encode(new Frame(new MessageHeader(messageId,
                    BodyProperties.plain(body.length), 1, terminalNo, serial), body)));
            socket.getOutputStream().flush();
        }

        Frame receive() throws Exception {
            List<Byte> bytes = new ArrayList<>();
            while (bytes.size() < 4096) {
                int value = socket.getInputStream().read();
                if (value < 0) throw new IllegalStateException("TCP closed before ACK");
                if (bytes.isEmpty() && value != 0x7e) continue;
                bytes.add((byte) value);
                if (value == 0x7e && bytes.size() > 1) {
                    byte[] frame = new byte[bytes.size()];
                    for (int i = 0; i < frame.length; i++) frame[i] = bytes.get(i);
                    return FrameCodec.decode(frame);
                }
            }
            throw new IllegalStateException("TCP frame exceeded limit");
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }
}
