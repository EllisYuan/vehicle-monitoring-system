package com.vms.track;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 启动与每日巡检预建未来分区，并持久记录每次执行结果。 */
@Component
public class TrackPartitionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(TrackPartitionMaintenance.class);

    private final JdbcTemplate jdbc;
    private final TrackPartitionCatalog catalog;
    private final TrackRetentionPolicy policy;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    public TrackPartitionMaintenance(JdbcTemplate jdbc, TrackPartitionCatalog catalog,
                                     TrackRetentionPolicy policy, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.policy = policy;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        run("STARTUP");
    }

    @Scheduled(cron = "${vms.track.daily-cron:0 5 0 * * *}", zone = "Asia/Shanghai")
    public void dailyInspection() {
        run("DAILY");
    }

    public MaintenanceResult run(String trigger) {
        LocalDateTime startedAt = LocalDateTime.now(clock);
        if (!lock.tryLock()) {
            log.warn("轨迹分区维护已在执行，跳过本次 trigger={}", trigger);
            return new MaintenanceResult(false, trigger, policy.retentionDays(), List.of(), List.of(), 0,
                    "已有分区维护任务在执行", null);
        }
        try {
            policy.refresh();
            int retentionDays = policy.retentionDays();
            LocalDate today = policy.today();
            LocalDate firstRetained = today.minusDays(retentionDays - 1L);
            List<String> created = catalog.ensureWindow(
                    firstRetained, today.plusDays(policy.properties().futureDays()));
            TrackPartitionCatalog.DropResult drop = catalog.dropBefore(firstRetained);
            Long runId = record(trigger, "SUCCESS", retentionDays, created,
                    drop.partitions(), drop.estimatedRows(), null, startedAt);
            log.info("轨迹分区维护完成 trigger={} retentionDays={} created={} dropped={}",
                    trigger, retentionDays, created, drop.partitions());
            return new MaintenanceResult(true, trigger, retentionDays, created, drop.partitions(),
                    drop.estimatedRows(), null, runId);
        } catch (RuntimeException e) {
            String message = truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), 1000);
            log.error("轨迹分区维护失败 trigger={} reason={}", trigger, message, e);
            Long runId = null;
            try {
                runId = record(trigger, "FAILED", safeRetentionDays(), List.of(), List.of(), 0, message, startedAt);
            } catch (RuntimeException recordFailure) {
                log.error("轨迹分区维护失败记录无法写入", recordFailure);
            }
            try {
                jdbc.update("""
                        INSERT INTO track_partition_alert (alert_type, message, detail, run_id, created_at)
                        VALUES ('PARTITION_MAINTENANCE_FAILED', ?, ?, ?, ?)
                        """, truncate(message, 500), e.getClass().getName(), runId, LocalDateTime.now(clock));
            } catch (RuntimeException alertFailure) {
                log.error("轨迹分区维护告警无法写入", alertFailure);
            }
            return new MaintenanceResult(false, trigger, safeRetentionDays(), List.of(), List.of(), 0, message, runId);
        } finally {
            lock.unlock();
        }
    }

    private int safeRetentionDays() {
        try {
            return policy.retentionDays();
        } catch (RuntimeException e) {
            return policy.properties().defaultRetentionDays();
        }
    }

    private Long record(String trigger, String status, int retentionDays, List<String> created,
                        List<String> dropped, long droppedRows, String message, LocalDateTime startedAt) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO track_partition_maintenance
                        (trigger_type, status, retention_days, created_partitions, dropped_partitions,
                         dropped_rows_estimate, message, started_at, finished_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, trigger);
            statement.setString(2, status);
            statement.setInt(3, retentionDays);
            statement.setString(4, joined(created));
            statement.setString(5, joined(dropped));
            statement.setLong(6, droppedRows);
            statement.setString(7, message);
            statement.setObject(8, startedAt);
            statement.setObject(9, LocalDateTime.now(clock));
            return statement;
        }, keys);
        Number key = keys.getKey();
        return key == null ? null : key.longValue();
    }

    private static String joined(List<String> names) {
        return names == null || names.isEmpty() ? null : String.join(",", names);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record MaintenanceResult(boolean success, String trigger, int retentionDays,
                                    List<String> createdPartitions, List<String> droppedPartitions,
                                    long droppedRowsEstimate, String message, Long runId) {
    }
}
