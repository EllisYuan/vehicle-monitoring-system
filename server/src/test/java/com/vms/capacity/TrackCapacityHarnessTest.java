package com.vms.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import com.vms.audit.AuditService;
import com.vms.identity.access.FleetScopeResolver;
import com.vms.identity.account.UserAccount;
import com.vms.track.TrackPartitionCatalog;
import com.vms.track.TrackPartitionMaintenance;
import com.vms.track.TrackQueryService;
import com.vms.track.TrackRetentionPolicy;
import com.vms.track.TrackRetentionProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 30 天量级轨迹容量 harness（AC33）。默认不进入常规测试轮次，显式命令：
 *
 * <pre>
 * mvnw.cmd -B -pl server -Dvms.capacity=true -Dtest=TrackCapacityHarnessTest \
 *     -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 *
 * 本 harness 使用独立 Testcontainers MySQL 8.4 容器，不连接开发库，不新增或修改 migration，
 * 生成数据使用独立车队/车辆/终端标识（CAP 前缀；terminal_no 受既有 CHECK 约束只能为 20 位数字，
 * 因此用 99 开头的 20 位编号），不写入任何正式初始化脚本。
 *
 * 可调参数（均通过 -D 传入）：
 * - vms.capacity.days：保留天数，默认 30
 * - vms.capacity.expired-days：额外生成的过期自然日数量，默认 3（用于清理计量）
 * - vms.capacity.terminals：终端数量，默认 100
 * - vms.capacity.points-per-day：每终端每日点数，默认 8640（10 秒间隔）
 * - vms.capacity.batch：多行 INSERT 批大小，默认 2000
 * - vms.capacity.generate：是否生成数据，默认 true；false 用于验证 harness 在无数据时拒绝通过
 *
 * 容量未达标时断言失败并写明“容量验证未完成”，不以小数据外推。
 */
@EnabledIfSystemProperty(named = "vms.capacity", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrackCapacityHarnessTest {

    /** AC33 目标：100 终端 × 8640 点/日 × 30 日 = 25,920,000 行。低于该值不得宣称通过。 */
    static final long REQUIRED_RETAINED_ROWS = 25_920_000L;

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    static final DateTimeFormatter PARTITION_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    static final DateTimeFormatter GPS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final DateTimeFormatter RECEIVED_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzz");

    /** 与 PositionReport 基础 28 字节一致的 0x0200 报文体模板（alarm/status/lat/lng/alt/speed/dir/time）。 */
    static final String RAW_BODY_HEX = "00000000000000030B8B0A280C1B1A52000000C8005A261116103000";

    static final Path EVIDENCE_DIR = Path.of("..", ".scratch", "vehicle-monitoring", "evidence", "logs")
            .toAbsolutePath().normalize();
    static final Path METRICS = EVIDENCE_DIR.resolve("25-capacity-metrics.log");
    static final Path DAY_CSV = EVIDENCE_DIR.resolve("25-capacity-days.csv");
    static final Path BATCH_CSV = EVIDENCE_DIR.resolve("25-capacity-batches.csv");

    static MySQLContainer<?> mysql;
    static HikariDataSource dataSource;
    static RecordingJdbcTemplate jdbc;
    static TrackQueryService trackQuery;
    static TrackPartitionCatalog catalog;
    static TrackPartitionMaintenance maintenance;
    static TrackRetentionPolicy retention;

    static BufferedWriter dayWriter;
    static BufferedWriter batchWriter;

    static LocalDate today;
    static int retainedDays;
    static int expiredDays;
    static int terminals;
    static int pointsPerDay;
    static int batchSize;
    static boolean generate;

    static long fleetId;
    static UserAccount admin;
    static final List<Long> terminalIds = new ArrayList<>();
    static final List<Long> vehicleIds = new ArrayList<>();

    static long expectedTotalRows;
    static long expectedRetainedRows;
    static long expectedExpiredRows;

    static GenerationResult generation;
    static QueryObservation singleDayQuery;
    static QueryObservation crossMidnightQuery;

    @BeforeAll
    static void startDedicatedEnvironment() throws Exception {
        retainedDays = Integer.getInteger("vms.capacity.days", 30);
        expiredDays = Integer.getInteger("vms.capacity.expired-days", 3);
        terminals = Integer.getInteger("vms.capacity.terminals", 100);
        pointsPerDay = Integer.getInteger("vms.capacity.points-per-day", 8640);
        batchSize = Integer.getInteger("vms.capacity.batch", 2000);
        generate = Boolean.parseBoolean(System.getProperty("vms.capacity.generate", "true"));
        today = LocalDate.now(BUSINESS_ZONE);

        Files.createDirectories(EVIDENCE_DIR);
        Files.writeString(METRICS, "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(DAY_CSV, "day,rows,batches,elapsed_ms,rows_per_second\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(BATCH_CSV,
                "day,terminal_no,batch_index,from_slot,to_slot,rows,build_ms,execute_ms,sql_bytes\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        dayWriter = Files.newBufferedWriter(DAY_CSV, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        batchWriter = Files.newBufferedWriter(BATCH_CSV, StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        metric("harness", "TrackCapacityHarnessTest");
        metric("started_at", ZonedDateTime.now(BUSINESS_ZONE).format(FILE_STAMP));
        metric("business_today", today);
        metric("parameters", "retainedDays=" + retainedDays + ", expiredDays=" + expiredDays
                + ", terminals=" + terminals + ", pointsPerDay=" + pointsPerDay + ", batchSize=" + batchSize
                + ", generate=" + generate);
        metric("required_retained_rows", REQUIRED_RETAINED_ROWS);

        mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("vms")
                .withUsername("vms")
                .withPassword("vmsdevpass")
                .withCommand("--skip-log-bin", "--innodb-buffer-pool-size=768M",
                        "--innodb-flush-log-at-trx-commit=2", "--innodb-log-buffer-size=64M",
                        "--innodb-doublewrite=OFF", "--max-connections=40", "--performance-schema=OFF")
                .withCreateContainerCmdModifier(command -> command.getHostConfig()
                        .withMemory(1200L * 1024 * 1024).withNanoCPUs(2_000_000_000L))
                .withUrlParam("connectionTimeZone", "Asia/Shanghai")
                .withUrlParam("forceConnectionTimeZoneToSession", "true")
                .withUrlParam("allowPublicKeyRetrieval", "true")
                .withUrlParam("useSSL", "false");
        mysql.start();

        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(mysql.getJdbcUrl());
        dataSource.setUsername(mysql.getUsername());
        dataSource.setPassword(mysql.getPassword());
        dataSource.setMaximumPoolSize(4);
        dataSource.setPoolName("capacity-harness");

        jdbc = new RecordingJdbcTemplate(dataSource);
        Flyway flyway = Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .load();
        int applied = flyway.migrate().migrationsExecuted;
        metric("flyway.migrationsExecuted", applied);
        assertThat(applied).as("容量 harness 必须应用正式 migration").isPositive();
        assertThat(tableExists("track_point")).as("migration 后 track_point 必须存在").isTrue();

        Clock clock = Clock.system(BUSINESS_ZONE);
        retention = new TrackRetentionPolicy(jdbc, clock, new TrackRetentionProperties(null, null, null, null, null));
        assertThat(retainedDays)
                .as("vms.capacity.days 必须与 system_config.track.retention.days 一致，否则分区窗口不自洽")
                .isEqualTo(retention.retentionDays());
        catalog = new TrackPartitionCatalog(jdbc);
        maintenance = new TrackPartitionMaintenance(jdbc, catalog, retention, clock);
        AuditService audit = new AuditService(jdbc, clock);
        trackQuery = new TrackQueryService(jdbc, new FleetScopeResolver(jdbc), retention, catalog, audit);

        recordEnvironment();
        createCapacityArchives();

        TrackPartitionMaintenance.MaintenanceResult startup = maintenance.run("STARTUP");
        assertThat(startup.success()).as("启动分区维护必须成功").isTrue();
        metric("startup_maintenance", "created=" + startup.createdPartitions()
                + ", dropped=" + startup.droppedPartitions() + ", retentionDays=" + startup.retentionDays());

        List<String> expiredNames = new ArrayList<>();
        if (expiredDays > 0) {
            LocalDate expiredStart = today.minusDays(retainedDays - 1L + expiredDays);
            LocalDate expiredEnd = today.minusDays(retainedDays);
            expiredNames = catalog.ensureWindow(expiredStart, expiredEnd);
            assertThat(expiredNames).as("过期自然日分区必须按同一目录组件创建").hasSize(expiredDays);
            metric("expired_partitions", String.join(",", expiredNames));
        }

        expectedRetainedRows = (long) retainedDays * terminals * pointsPerDay;
        expectedExpiredRows = (long) expiredDays * terminals * pointsPerDay;
        expectedTotalRows = expectedRetainedRows + expectedExpiredRows;
        metric("expected_rows", "retained=" + expectedRetainedRows + ", expired=" + expectedExpiredRows
                + ", total=" + expectedTotalRows);
        recordHostDisk("disk_before_generation");
    }

    @AfterAll
    static void stopDedicatedEnvironment() throws IOException {
        if (batchWriter != null) {
            batchWriter.flush();
            batchWriter.close();
        }
        if (dayWriter != null) {
            dayWriter.flush();
            dayWriter.close();
        }
        if (dataSource != null) {
            dataSource.close();
        }
        if (mysql != null) {
            mysql.stop();
        }
        metric("finished_at", ZonedDateTime.now(BUSINESS_ZONE).format(FILE_STAMP));
        if (!LocalDate.now(BUSINESS_ZONE).equals(today)) {
            metric("warning", "本次运行跨越业务日边界：start=" + today + ", end=" + LocalDate.now(BUSINESS_ZONE));
        }
    }

    @Test
    @Order(1)
    void generatedDatasetReachesThirtyDayCapacityTarget() {
        generation = generate ? generateAllDays() : new GenerationResult(0, 0, 0);

        long actualTotal = count("track_point");
        long actualRetained = countPartitions(retainedPartitionNames());
        metric("generated.total_rows", actualTotal);
        metric("generated.retained_rows", actualRetained);
        metric("generated.expected_total_rows", expectedTotalRows);
        metric("generated.expected_retained_rows", expectedRetainedRows);
        metric("generated.total_elapsed_ms", generation.elapsedMs());
        metric("generated.rows_per_second", generation.rowsPerSecond());
        metric("generated.batches", generation.batches());
        metric("generated.batch_size", batchSize);

        assertThat(actualRetained)
                .as("生成保留窗口行数必须与参数推导值一致")
                .isEqualTo(expectedRetainedRows);
        if (expectedRetainedRows >= REQUIRED_RETAINED_ROWS) {
            assertThat(actualRetained)
                    .as("容量验证未完成：保留窗口实际 %d 行，低于 30 天目标 %d 行；"
                            + "不得以较小数据外推容量结论", actualRetained, REQUIRED_RETAINED_ROWS)
                    .isGreaterThanOrEqualTo(REQUIRED_RETAINED_ROWS);
        } else {
            metric("capacity.scale", "非验收规模：expectedRetainedRows=" + expectedRetainedRows
                    + " < " + REQUIRED_RETAINED_ROWS + "，仅校验自洽性，不得据此宣称 AC33 通过");
        }
        assertThat(actualTotal).as("生成总行数必须等于参数推导值").isEqualTo(expectedTotalRows);
        assertThat(generation.rows()).isEqualTo(expectedTotalRows);
    }

    @Test
    @Order(2)
    void capacityArchivesAndPartitionWindowAreDedicatedAndRecorded() {
        assertThat(count("fleet")).as("容量容器只允许本 harness 的独立车队档案").isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT fleet_code FROM fleet", String.class)).isEqualTo("CAP-FLEET");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM vehicle WHERE plate_no LIKE 'CAP-%'", Long.class)).isEqualTo(terminals);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM vehicle WHERE plate_no NOT LIKE 'CAP-%'", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM terminal WHERE terminal_no LIKE '99%'", Long.class)).isEqualTo(terminals);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM terminal_vehicle_binding", Long.class)).isEqualTo(terminals);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM vehicle_fleet_history", Long.class)).isEqualTo(terminals);
        assertThat(jdbc.queryForObject(
                "SELECT config_value FROM system_config WHERE config_key = 'track.retention.days'", String.class))
                .isEqualTo("30");

        List<String> actual = partitionNames();
        List<String> expected = new ArrayList<>(expiredPartitionNames());
        expected.addAll(retainedPartitionNames());
        for (int daysAhead = 1; daysAhead <= 7; daysAhead++) {
            expected.add(partitionName(today.plusDays(daysAhead)));
        }

        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        metric("partitions.count", actual.size());
        metric("partitions.first", actual.getFirst());
        metric("partitions.last", actual.getLast());
        metric("partitions.retained_first", retainedPartitionNames().getFirst());
        metric("partitions.retained_last", retainedPartitionNames().getLast());

        Map<String, Object> startup = jdbc.queryForMap(
                "SELECT * FROM track_partition_maintenance WHERE trigger_type = 'STARTUP' ORDER BY id LIMIT 1");
        assertThat(startup.get("status")).isEqualTo("SUCCESS");
        assertThat(((Number) startup.get("retention_days")).intValue()).isEqualTo(30);

        long invalidArchives = jdbc.queryForObject(
                "SELECT count(*) FROM vehicle v JOIN fleet f ON f.id = v.fleet_id"
                        + " WHERE f.fleet_code <> 'CAP-FLEET'", Long.class);
        assertThat(invalidArchives).isZero();
    }

    @Test
    @Order(3)
    void tableIndexFootprintAndPartitionRowsAreRecorded() {
        Map<String, Object> status = jdbc.queryForMap(
                "SELECT TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH, DATA_FREE, AVG_ROW_LENGTH"
                        + " FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()"
                        + " AND TABLE_NAME = 'track_point'");
        long dataLength = ((Number) status.get("DATA_LENGTH")).longValue();
        long indexLength = ((Number) status.get("INDEX_LENGTH")).longValue();
        long footprint = dataLength + indexLength;
        metric("table.track_point.estimated_rows", status.get("TABLE_ROWS"));
        metric("table.track_point.data_length_bytes", dataLength);
        metric("table.track_point.index_length_bytes", indexLength);
        metric("table.track_point.total_bytes", footprint);
        metric("table.track_point.data_free_bytes", status.get("DATA_FREE"));
        metric("table.track_point.avg_row_length_bytes", status.get("AVG_ROW_LENGTH"));

        List<Map<String, Object>> show = jdbc.queryForList("SHOW TABLE STATUS LIKE 'track_point'");
        assertThat(show).hasSize(1);
        metric("table.track_point.engine", show.getFirst().get("Engine"));
        metric("table.track_point.collation", show.getFirst().get("Collation"));

        long diskKb = containerTrackPointKb();
        metric("table.track_point.container_du_kb", diskKb);
        metric("table.track_point.container_du_mib", diskKb < 0 ? "unavailable" : diskKb / 1024.0);
        if (diskKb > 0) {
            long minimumBytes = expectedTotalRows * 100L;
            assertThat(diskKb * 1024L)
                    .as("真实生成数据必须产生可计量磁盘占用（保守下限 100 字节/行 × %d 行）", expectedTotalRows)
                    .isGreaterThanOrEqualTo(minimumBytes);
        }

        Map<String, Object> exact = jdbc.queryForMap("SELECT count(*) AS total,"
                + " sum(CASE WHEN locate_state = 'VALID' THEN 1 ELSE 0 END) AS valid_points,"
                + " sum(CASE WHEN locate_state = 'INVALID' THEN 1 ELSE 0 END) AS invalid_points,"
                + " sum(CASE WHEN alarm_flag <> 0 THEN 1 ELSE 0 END) AS event_points"
                + " FROM track_point");
        metric("rows.total", exact.get("total"));
        metric("rows.valid", exact.get("valid_points"));
        metric("rows.invalid", exact.get("invalid_points"));
        metric("rows.event_flagged", exact.get("event_points"));
        assertThat(((Number) exact.get("total")).longValue()).isEqualTo(expectedTotalRows);

        List<Map<String, Object>> partitionRows = jdbc.queryForList(
                "SELECT PARTITION_NAME, PARTITION_DESCRIPTION, PARTITION_ORDINAL_POSITION, TABLE_ROWS,"
                        + " DATA_LENGTH, INDEX_LENGTH FROM information_schema.PARTITIONS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'track_point'"
                        + " AND PARTITION_NAME IS NOT NULL ORDER BY PARTITION_ORDINAL_POSITION");
        for (Map<String, Object> row : partitionRows) {
            metric("partition", row.get("PARTITION_ORDINAL_POSITION") + " " + row.get("PARTITION_NAME")
                    + " " + row.get("PARTITION_DESCRIPTION") + " rows~" + row.get("TABLE_ROWS")
                    + " data=" + row.get("DATA_LENGTH") + " index=" + row.get("INDEX_LENGTH"));
        }
        assertThat(partitionRows).hasSize(retainedDays + 7 + expiredDays);
        recordHostDisk("disk_after_generation");
        metric("container.disk_after_generation", containerDiskUsage());
    }

    @Test
    @Order(4)
    void singleDayAndCrossMidnightQueriesPrunePartitionsAndRecordPlans() {
        LocalDate day = today.minusDays(10);
        LocalDate nextDay = day.plusDays(1);
        long vehicleId = vehicleIds.getFirst();
        long intervalSeconds = 86400L / pointsPerDay;
        long firstSlotAtTwenty = (72000L + intervalSeconds - 1) / intervalSeconds;
        long crossMidnightPoints = (pointsPerDay - firstSlotAtTwenty) + (14400L / intervalSeconds + 1);
        metric("query.cross-midnight.expected_points", crossMidnightPoints);

        singleDayQuery = observeQuery("single-day", vehicleId,
                day.atTime(0, 0, 0), day.atTime(23, 59, 59),
                Set.of(partitionName(day)), pointsPerDay);

        crossMidnightQuery = observeQuery("cross-midnight-8h", vehicleId,
                day.atTime(20, 0, 0), nextDay.atTime(4, 0, 0),
                Set.of(partitionName(day), partitionName(nextDay)), crossMidnightPoints);
    }

    @Test
    @Order(5)
    void expiredPartitionCleanupPreservesRetainedDataAndQueries() {
        Assumptions.assumeTrue(expiredDays > 0, "未生成过期分区，跳过清理计量");

        List<String> expiredNames = expiredPartitionNames();
        List<String> retainedNames = retainedPartitionNames();
        long retainedBefore = countPartitions(retainedNames);
        long expiredBefore = countPartitions(expiredNames);
        long totalBefore = count("track_point");
        long diskBefore = containerTrackPointKb();
        metric("cleanup.before.partitions", partitionNames().size());
        metric("cleanup.before.retained_rows", retainedBefore);
        metric("cleanup.before.expired_rows", expiredBefore);
        metric("cleanup.before.total_rows", totalBefore);
        metric("cleanup.before.container_du_kb", diskBefore);
        metric("cleanup.before.alerts", count("track_partition_alert"));
        recordHostDisk("disk_before_cleanup");

        long started = System.nanoTime();
        TrackPartitionMaintenance.MaintenanceResult result = maintenance.run("MANUAL");
        long cleanupMs = (System.nanoTime() - started) / 1_000_000L;
        metric("cleanup.elapsed_ms", cleanupMs);
        metric("cleanup.result_success", result.success());
        metric("cleanup.created_partitions", String.join(",", result.createdPartitions()));
        metric("cleanup.dropped_partitions", String.join(",", result.droppedPartitions()));
        metric("cleanup.dropped_rows_estimate", result.droppedRowsEstimate());

        assertThat(result.success()).as("过期分区清理必须成功").isTrue();
        assertThat(result.droppedPartitions()).containsExactlyInAnyOrderElementsOf(expiredNames);

        List<String> after = partitionNames();
        metric("cleanup.after.partitions", after.size());
        assertThat(after).doesNotContainAnyElementsOf(expiredNames);
        assertThat(after).containsAll(retainedNames);

        long retainedAfter = countPartitions(retainedNames);
        long totalAfter = count("track_point");
        long diskAfter = containerTrackPointKb();
        metric("cleanup.after.retained_rows", retainedAfter);
        metric("cleanup.after.total_rows", totalAfter);
        metric("cleanup.after.container_du_kb", diskAfter);
        metric("cleanup.after.alerts", count("track_partition_alert"));
        assertThat(retainedAfter).as("清理不得改变保留分区行数").isEqualTo(retainedBefore);
        assertThat(totalAfter).isEqualTo(retainedBefore);
        assertThat(totalAfter).isEqualTo(expectedRetainedRows);
        if (diskBefore > 0 && diskAfter > 0) {
            assertThat(diskAfter).as("清理过期分区必须释放磁盘占用").isLessThan(diskBefore);
            metric("cleanup.released_bytes", (diskBefore - diskAfter) * 1024L);
        }

        Map<String, Object> run = jdbc.queryForMap(
                "SELECT * FROM track_partition_maintenance WHERE id = ?", result.runId());
        assertThat(run.get("status")).isEqualTo("SUCCESS");
        assertThat(run.get("finished_at")).isNotNull();
        metric("cleanup.recorded.dropped_partitions", run.get("dropped_partitions"));
        metric("cleanup.recorded.dropped_rows_estimate", run.get("dropped_rows_estimate"));
        assertThat(count("track_partition_alert")).as("成功清理不应产生失败告警").isZero();

        verifyQueryStillUsable("after-cleanup-single-day", singleDayQuery);
        verifyQueryStillUsable("after-cleanup-cross-midnight", crossMidnightQuery);
        recordHostDisk("disk_after_cleanup");
        metric("container.disk_after_cleanup", containerDiskUsage());
    }

    // ------------------------------------------------------------------
    // 生成
    // ------------------------------------------------------------------

    static GenerationResult generateAllDays() {
        long runStart = System.nanoTime();
        long totalRows = 0;
        long totalBatches = 0;
        LocalDate firstDay = today.minusDays(retainedDays - 1L + expiredDays);

        for (LocalDate day = firstDay; !day.isAfter(today); day = day.plusDays(1)) {
            long dayStart = System.nanoTime();
            long dayRows = 0;
            long dayBatches = 0;
            String[] gpsTimes = new String[pointsPerDay];
            String[] receivedTimes = new String[pointsPerDay];
            String[] alarms = new String[pointsPerDay];
            String[] states = new String[pointsPerDay];
            for (int slot = 0; slot < pointsPerDay; slot++) {
                LocalDateTime gps = day.atStartOfDay().plusSeconds(10L * slot);
                gpsTimes[slot] = gps.format(GPS_FORMAT);
                receivedTimes[slot] = gps.plusNanos(500_000_000L).format(RECEIVED_FORMAT);
                alarms[slot] = slot % 1000 == 0 ? "1" : "0";
                states[slot] = slot % 3600 == 0 ? "INVALID" : "VALID";
            }

            for (int index = 0; index < terminals; index++) {
                long terminalId = terminalIds.get(index);
                long vehicleId = vehicleIds.get(index);
                String[] lngs = new String[pointsPerDay];
                String[] lats = new String[pointsPerDay];
                String[] hashes = new String[pointsPerDay];
                double baseLng = 121.40 + (index % 10) * 0.05;
                double baseLat = 31.20 + (index / 10) * 0.02;
                for (int slot = 0; slot < pointsPerDay; slot++) {
                    if ("INVALID".equals(states[slot])) {
                        lngs[slot] = "NULL";
                        lats[slot] = "NULL";
                    } else {
                        double lng = baseLng + 0.02 * Math.sin(slot / 500.0);
                        double lat = baseLat + 0.01 * Math.cos(slot / 700.0);
                        lngs[slot] = Double.toString(Math.round(lng * 1_000_000d) / 1_000_000d);
                        lats[slot] = Double.toString(Math.round(lat * 1_000_000d) / 1_000_000d);
                    }
                    hashes[slot] = pseudoPayloadHash(terminalId, day, slot);
                }

                int batchIndex = 0;
                for (int from = 0; from < pointsPerDay; from += batchSize) {
                    int to = Math.min(pointsPerDay, from + batchSize);
                    long buildStart = System.nanoTime();
                    String sql = buildBatchSql(terminalId, vehicleId, from, to,
                            gpsTimes, receivedTimes, lngs, lats, hashes, alarms, states);
                    long buildMs = (System.nanoTime() - buildStart) / 1_000_000L;
                    long executeStart = System.nanoTime();
                    jdbc.update(sql);
                    long executeMs = (System.nanoTime() - executeStart) / 1_000_000L;
                    appendBatchCsv(day, index, batchIndex, from, to, to - from, buildMs, executeMs, sql.length());
                    dayRows += to - from;
                    dayBatches++;
                    batchIndex++;
                }
            }

            long dayElapsedMs = (System.nanoTime() - dayStart) / 1_000_000L;
            double rowsPerSecond = dayElapsedMs == 0 ? 0 : dayRows * 1000.0 / dayElapsedMs;
            appendDayCsv(day, dayRows, dayBatches, dayElapsedMs, rowsPerSecond);
            flushQuietly();
            metric("generation.day", day + " rows=" + dayRows + " batches=" + dayBatches
                    + " elapsedMs=" + dayElapsedMs + " rowsPerSecond="
                    + String.format(Locale.ROOT, "%.1f", rowsPerSecond));
            totalRows += dayRows;
            totalBatches += dayBatches;
        }

        long elapsedMs = (System.nanoTime() - runStart) / 1_000_000L;
        return new GenerationResult(totalRows, totalBatches, elapsedMs);
    }

    static String buildBatchSql(long terminalId, long vehicleId, int fromSlot, int toSlot,
                                String[] gpsTimes, String[] receivedTimes, String[] lngs, String[] lats,
                                String[] hashes, String[] alarms, String[] states) {
        StringBuilder sql = new StringBuilder((toSlot - fromSlot) * 240 + 256);
        sql.append("INSERT INTO track_point (terminal_id, vehicle_id, fleet_id_at_event, gps_time, received_at,")
                .append(" lng, lat, speed, direction, alarm_flag, status_flag, locate_state, report_type,")
                .append(" payload_hash, raw_body) VALUES ");
        for (int slot = fromSlot; slot < toSlot; slot++) {
            if (slot > fromSlot) {
                sql.append(',');
            }
            sql.append('(').append(terminalId).append(',').append(vehicleId).append(',').append(fleetId)
                    .append(",'").append(gpsTimes[slot]).append("','").append(receivedTimes[slot]).append("',")
                    .append(lngs[slot]).append(',').append(lats[slot])
                    .append(",30.0,").append((slot * 7) % 360)
                    .append(',').append(alarms[slot]).append(",3,'").append(states[slot])
                    .append("','REALTIME','").append(hashes[slot]).append("',X'").append(RAW_BODY_HEX).append("')");
        }
        return sql.toString();
    }

    static String pseudoPayloadHash(long terminalId, LocalDate day, int slot) {
        long seed = terminalId * 1_000_003L + day.toEpochDay() * 86_400L + slot;
        char[] hex = new char[64];
        String digits = "0123456789abcdef";
        for (int block = 0; block < 4; block++) {
            long value = mix64(seed + block * 0x9E3779B97F4A7C15L);
            for (int nibble = 0; nibble < 16; nibble++) {
                hex[block * 16 + nibble] = digits.charAt((int) (value & 0xF));
                value >>>= 4;
            }
        }
        return new String(hex);
    }

    static long mix64(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    // ------------------------------------------------------------------
    // 查询观测
    // ------------------------------------------------------------------

    static QueryObservation observeQuery(String label, long vehicleId, LocalDateTime from, LocalDateTime to,
                                         Set<String> expectedPartitions, long expectedRawPoints) {
        OffsetDateTime fromOffset = from.atZone(BUSINESS_ZONE).toOffsetDateTime();
        OffsetDateTime toOffset = to.atZone(BUSINESS_ZONE).toOffsetDateTime();

        jdbc.clearCaptured();
        long coldStart = System.nanoTime();
        TrackQueryService.TrackView cold = trackQuery.query(admin, vehicleId, fromOffset, toOffset);
        long coldMs = (System.nanoTime() - coldStart) / 1_000_000L;
        String capturedSql = jdbc.capturedSql();
        Object[] capturedArgs = jdbc.capturedArgs();
        assertThat(capturedSql).as("必须捕获到 ticket 10 生产查询 SQL").isNotBlank();

        long warmStart = System.nanoTime();
        TrackQueryService.TrackView warm = trackQuery.query(admin, vehicleId, fromOffset, toOffset);
        long warmMs = (System.nanoTime() - warmStart) / 1_000_000L;

        assertThat(cold.rawPointCount()).as(label + " 原始点数必须与生成参数一致").isEqualTo(expectedRawPoints);
        assertThat(warm.rawPointCount()).isEqualTo(cold.rawPointCount());
        assertThat(warm.displayPointCount()).isEqualTo(cold.displayPointCount());
        assertThat(cold.displayPointCount()).as(label + " 展示点不得超过 2000").isLessThanOrEqualTo(2000);
        assertThat(cold.segments()).isNotEmpty();

        List<Map<String, Object>> plan = explain(capturedSql, capturedArgs);
        Set<String> pruned = prunedPartitions(plan);
        for (Map<String, Object> row : plan) {
            metric("explain." + label, row.toString());
        }
        metric("query." + label + ".sql", capturedSql);
        metric("query." + label + ".args", Arrays.toString(capturedArgs));
        metric("query." + label + ".pruned_partitions", String.join(",", pruned));
        metric("query." + label + ".expected_partitions", String.join(",", expectedPartitions));
        metric("query." + label + ".cold_ms", coldMs);
        metric("query." + label + ".warm_ms", warmMs);
        metric("query." + label + ".raw_points", cold.rawPointCount());
        metric("query." + label + ".display_points", cold.displayPointCount());
        metric("query." + label + ".segments", cold.segments().size());
        metric("query." + label + ".gaps", cold.gaps().size());

        assertThat(pruned).as(label + " EXPLAIN partitions 必须与查询日窗口一致（分区裁剪生效）")
                .containsExactlyInAnyOrderElementsOf(expectedPartitions);
        assertThat(pruned).as(label + " 不得出现全分区扫描（partitions 为空）").isNotEmpty();
        return new QueryObservation(label, fromOffset, toOffset, cold.rawPointCount(),
                cold.displayPointCount(), cold.segments().size(), cold.gaps().size(), coldMs, warmMs, pruned);
    }

    static void verifyQueryStillUsable(String label, QueryObservation observation) {
        long started = System.nanoTime();
        TrackQueryService.TrackView view = trackQuery.query(admin, vehicleIds.getFirst(),
                observation.from(), observation.to());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        metric("query." + label + ".elapsed_ms", elapsedMs);
        metric("query." + label + ".raw_points", view.rawPointCount());
        metric("query." + label + ".display_points", view.displayPointCount());
        assertThat(view.rawPointCount()).isEqualTo(observation.rawPointCount());
        assertThat(view.displayPointCount()).isEqualTo(observation.displayPointCount());
    }

    static List<Map<String, Object>> explain(String sql, Object[] args) {
        try {
            return jdbc.queryForList("EXPLAIN " + sql, args);
        } catch (RuntimeException e) {
            metric("explain.prepared_unavailable", e.getClass().getSimpleName() + ": " + e.getMessage());
            String literal = inlineArguments(sql, args);
            metric("explain.literal_sql", literal);
            return jdbc.queryForList("EXPLAIN " + literal);
        }
    }

    static Set<String> prunedPartitions(List<Map<String, Object>> plan) {
        Set<String> result = new LinkedHashSet<>();
        for (Map<String, Object> row : plan) {
            Object partitions = row.get("partitions");
            if (partitions == null) {
                continue;
            }
            for (String name : partitions.toString().split(",")) {
                if (!name.isBlank()) {
                    result.add(name.trim());
                }
            }
        }
        return result;
    }

    static String inlineArguments(String sql, Object[] args) {
        String result = sql;
        if (args == null) {
            return result;
        }
        for (Object arg : args) {
            int index = result.indexOf('?');
            if (index < 0) {
                break;
            }
            String literal;
            if (arg instanceof Number number) {
                literal = number.toString();
            } else if (arg instanceof LocalDateTime time) {
                literal = "'" + time.format(GPS_FORMAT) + "'";
            } else if (arg instanceof OffsetDateTime time) {
                literal = "'" + time.withOffsetSameInstant(ZoneOffset.ofHours(8))
                        .toLocalDateTime().format(GPS_FORMAT) + "'";
            } else {
                literal = "'" + arg.toString().replace("'", "''") + "'";
            }
            result = result.substring(0, index) + literal + result.substring(index + 1);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 档案与环境
    // ------------------------------------------------------------------

    static void createCapacityArchives() {
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        LocalDateTime effectiveFrom = today.minusDays(120).atStartOfDay();
        jdbc.update("INSERT INTO fleet (fleet_code, fleet_name, status, created_at, updated_at)"
                + " VALUES ('CAP-FLEET', 'CAP 容量验收车队', 'ENABLED', ?, ?)", now, now);
        fleetId = jdbc.queryForObject("SELECT id FROM fleet WHERE fleet_code = 'CAP-FLEET'", Long.class);

        jdbc.update("INSERT INTO sys_user (username, password_hash, role_code, status, auth_version,"
                        + " created_at, updated_at) VALUES (?, ?, 'ADMIN', 'ENABLED', 1, ?, ?)",
                "cap-capacity-admin", "capacity-harness-no-login", now, now);
        admin = new UserAccount();
        admin.setId(jdbc.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'cap-capacity-admin'", Long.class));
        admin.setUsername("cap-capacity-admin");
        admin.setRoleCode("ADMIN");
        admin.setStatus("ENABLED");

        for (int index = 1; index <= terminals; index++) {
            String terminalNo = String.format(Locale.ROOT, "99%018d", index);
            jdbc.update("INSERT INTO terminal (terminal_no, status, ever_authenticated, created_at, updated_at)"
                    + " VALUES (?, 'ENABLED', FALSE, ?, ?)", terminalNo, now, now);
            long terminalId = jdbc.queryForObject(
                    "SELECT id FROM terminal WHERE terminal_no = ?", Long.class, terminalNo);

            String plateNo = String.format(Locale.ROOT, "CAP-%03d", index);
            jdbc.update("INSERT INTO vehicle (plate_no, fleet_id, status, created_at, updated_at)"
                    + " VALUES (?, ?, 'ENABLED', ?, ?)", plateNo, fleetId, now, now);
            long vehicleId = jdbc.queryForObject(
                    "SELECT id FROM vehicle WHERE plate_no = ?", Long.class, plateNo);

            jdbc.update("INSERT INTO terminal_vehicle_binding (terminal_id, vehicle_id, effective_from)"
                    + " VALUES (?, ?, ?)", terminalId, vehicleId, effectiveFrom);
            jdbc.update("INSERT INTO vehicle_fleet_history (vehicle_id, fleet_id, effective_from)"
                    + " VALUES (?, ?, ?)", vehicleId, fleetId, effectiveFrom);
            terminalIds.add(terminalId);
            vehicleIds.add(vehicleId);
        }
        metric("archives", "fleet=CAP-FLEET, vehicles=" + vehicleIds.size() + ", terminals=" + terminalIds.size()
                + ", admin=" + admin.getUsername());
    }

    static void recordEnvironment() {
        Map<String, Object> vars = jdbc.queryForMap("SELECT @@version AS version,"
                + " @@innodb_buffer_pool_size AS buffer_pool, @@log_bin AS log_bin,"
                + " @@innodb_flush_log_at_trx_commit AS flush_log, @@max_connections AS max_connections,"
                + " @@performance_schema AS performance_schema, @@character_set_server AS charset,"
                + " @@collation_server AS collation, @@time_zone AS session_time_zone,"
                + " @@innodb_file_per_table AS file_per_table, @@datadir AS datadir,"
                + " @@max_allowed_packet AS max_allowed_packet");
        metric("mysql.max_allowed_packet", vars.get("max_allowed_packet"));
        metric("mysql.version", vars.get("version"));
        metric("mysql.innodb_buffer_pool_size", vars.get("buffer_pool"));
        metric("mysql.log_bin", vars.get("log_bin"));
        metric("mysql.innodb_flush_log_at_trx_commit", vars.get("flush_log"));
        metric("mysql.max_connections", vars.get("max_connections"));
        metric("mysql.performance_schema", vars.get("performance_schema"));
        metric("mysql.charset", vars.get("charset") + "/" + vars.get("collation"));
        metric("mysql.session_time_zone", vars.get("session_time_zone"));
        metric("mysql.innodb_file_per_table", vars.get("file_per_table"));
        metric("mysql.datadir", vars.get("datadir"));

        metric("host.os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        metric("host.java", System.getProperty("java.version"));
        metric("host.cpus", Runtime.getRuntime().availableProcessors());
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            metric("host.memory_total_bytes", sunOs.getTotalMemorySize());
            metric("host.memory_free_bytes", sunOs.getFreeMemorySize());
        }
        metric("container.id", mysql.getContainerId());
        metric("container.image", mysql.getDockerImageName());
        metric("container.command", "--skip-log-bin --innodb-buffer-pool-size=768M"
                + " --innodb-flush-log-at-trx-commit=2 --innodb-log-buffer-size=64M"
                + " --innodb-doublewrite=OFF --max-connections=40 --performance-schema=OFF");
        metric("container.inspect", docker("inspect", "--format",
                "memory={{.HostConfig.Memory}} nanocpus={{.HostConfig.NanoCpus}} image={{.Image}}",
                mysql.getContainerId()));
        metric("docker.info", docker("info", "--format",
                "memTotal={{.MemTotal}} ncpu={{.NCPU}} server={{.ServerVersion}}"));
        metric("docker.version", docker("version", "--format", "{{.Server.Version}}"));
    }

    static void recordHostDisk(String label) {
        try {
            java.nio.file.FileStore store = Files.getFileStore(Path.of("C:\\"));
            metric(label, "usable=" + store.getUsableSpace() + ", total=" + store.getTotalSpace());
        } catch (IOException e) {
            metric(label, "unavailable: " + e.getMessage());
        }
    }

    static String containerDiskUsage() {
        return docker("exec", mysql.getContainerId(), "df", "-k", "/var/lib/mysql");
    }

    /** track_point 各分区 .ibd 的实际磁盘占用（KiB）；不可用时返回 -1。 */
    static long containerTrackPointKb() {
        try {
            String datadir = jdbc.queryForObject("SELECT @@datadir", String.class);
            String script = "du -sk " + datadir + "vms/track_point* | awk '{s+=$1} END {print s}'";
            String output = docker("exec", mysql.getContainerId(), "sh", "-c", script);
            return Long.parseLong(output.trim());
        } catch (RuntimeException e) {
            metric("table.track_point.container_du_error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return -1L;
        }
    }

    static String docker(String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("docker");
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "timeout";
            }
            return output.replace('\n', ' ');
        } catch (IOException e) {
            return "unavailable: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    // ------------------------------------------------------------------
    // 分区与计数辅助
    // ------------------------------------------------------------------

    static List<String> partitionNames() {
        return jdbc.queryForList("SELECT PARTITION_NAME FROM information_schema.PARTITIONS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'track_point'"
                + " AND PARTITION_NAME IS NOT NULL ORDER BY PARTITION_ORDINAL_POSITION", String.class);
    }

    static List<String> retainedPartitionNames() {
        List<String> names = new ArrayList<>();
        for (int daysAgo = retainedDays - 1; daysAgo >= 0; daysAgo--) {
            names.add(partitionName(today.minusDays(daysAgo)));
        }
        return names;
    }

    static List<String> expiredPartitionNames() {
        List<String> names = new ArrayList<>();
        for (int daysAgo = retainedDays + expiredDays - 1; daysAgo >= retainedDays; daysAgo--) {
            names.add(partitionName(today.minusDays(daysAgo)));
        }
        return names;
    }

    static String partitionName(LocalDate day) {
        return "p" + day.format(PARTITION_FORMAT);
    }

    static long count(String table) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    static long countPartitions(List<String> names) {
        if (names.isEmpty()) {
            return 0;
        }
        Long value = jdbc.queryForObject("SELECT count(*) FROM track_point PARTITION ("
                + String.join(",", names) + ")", Long.class);
        return value == null ? 0 : value;
    }

    static boolean tableExists(String table) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM information_schema.TABLES"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", Integer.class, table);
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------
    // 记录输出
    // ------------------------------------------------------------------

    static synchronized void metric(String key, Object value) {
        String line = key + "=" + value;
        System.out.println("[capacity] " + line);
        try {
            Files.writeString(METRICS, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[capacity] metrics write failed: " + e.getMessage());
        }
    }

    static synchronized void appendDayCsv(LocalDate day, long rows, long batches, long elapsedMs,
                                          double rowsPerSecond) {
        append(dayWriter, day + "," + rows + "," + batches + "," + elapsedMs + ","
                + String.format(Locale.ROOT, "%.1f", rowsPerSecond) + System.lineSeparator());
    }

    static synchronized void appendBatchCsv(LocalDate day, int terminalIndex, int batchIndex,
                                            int fromSlot, int toSlot, int rows, long buildMs, long executeMs,
                                            int sqlBytes) {
        String terminalNo = String.format(Locale.ROOT, "99%018d", terminalIndex + 1);
        append(batchWriter, day + "," + terminalNo + "," + batchIndex + "," + fromSlot + "," + toSlot + ","
                + rows + "," + buildMs + "," + executeMs + "," + sqlBytes + System.lineSeparator());
    }

    static void append(BufferedWriter writer, String line) {
        try {
            writer.write(line);
        } catch (IOException e) {
            System.out.println("[capacity] csv write failed: " + e.getMessage());
        }
    }

    static void flushQuietly() {
        try {
            if (dayWriter != null) {
                dayWriter.flush();
            }
            if (batchWriter != null) {
                batchWriter.flush();
            }
        } catch (IOException e) {
            System.out.println("[capacity] csv flush failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 记录类型
    // ------------------------------------------------------------------

    record GenerationResult(long rows, long batches, long elapsedMs) {
        double rowsPerSecond() {
            return elapsedMs == 0 ? 0 : rows * 1000.0 / elapsedMs;
        }
    }

    record QueryObservation(String label, OffsetDateTime from, OffsetDateTime to, int rawPointCount,
                            int displayPointCount, int segmentCount, int gapCount, long coldMs, long warmMs,
                            Set<String> partitions) {
    }

    /** 捕获 ticket 10 生产查询实际执行的 SQL 与参数，供 EXPLAIN 使用。 */
    static class RecordingJdbcTemplate extends JdbcTemplate {
        private volatile String capturedSql;
        private volatile Object[] capturedArgs;

        RecordingJdbcTemplate(HikariDataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) throws DataAccessException {
            this.capturedSql = sql;
            this.capturedArgs = args == null ? new Object[0] : args.clone();
            return super.query(sql, rowMapper, args);
        }

        void clearCaptured() {
            this.capturedSql = null;
            this.capturedArgs = null;
        }

        String capturedSql() {
            return capturedSql;
        }

        Object[] capturedArgs() {
            return capturedArgs;
        }
    }
}
