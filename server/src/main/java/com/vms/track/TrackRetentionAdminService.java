package com.vms.track;

import com.vms.audit.AuditService;
import com.vms.identity.account.UserAccount;
import com.vms.infrastructure.web.BadRequestException;
import com.vms.infrastructure.web.ConflictException;
import com.vms.infrastructure.web.NotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 缩短保留期的预览、管理员确认与维护记录查询。 */
@Service
public class TrackRetentionAdminService {

    public static final String CATEGORY = "TRACK_RETENTION";
    private static final int PREVIEW_TTL_MINUTES = 30;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TrackRetentionPolicy policy;
    private final TrackPartitionCatalog catalog;
    private final TrackPartitionMaintenance maintenance;
    private final AuditService auditService;

    public TrackRetentionAdminService(JdbcTemplate jdbc, Clock clock, TrackRetentionPolicy policy,
                                      TrackPartitionCatalog catalog, TrackPartitionMaintenance maintenance,
                                      AuditService auditService) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.policy = policy;
        this.catalog = catalog;
        this.maintenance = maintenance;
        this.auditService = auditService;
    }

    public OverviewView overview() {
        int currentDays = policy.retentionDays();
        List<String> runs = jdbc.query("""
                SELECT status, trigger_type, retention_days, created_partitions, dropped_partitions,
                       dropped_rows_estimate, message, started_at, finished_at
                FROM track_partition_maintenance ORDER BY id DESC LIMIT 20
                """, (rs, row) -> rs.getString("started_at") + " " + rs.getString("trigger_type") + " "
                + rs.getString("status") + " 保留" + rs.getInt("retention_days") + "天 created="
                + value(rs.getString("created_partitions")) + " dropped=" + value(rs.getString("dropped_partitions"))
                + (rs.getString("message") == null ? "" : " message=" + rs.getString("message")));
        List<String> alerts = jdbc.query("""
                SELECT alert_type, message, created_at FROM track_partition_alert
                ORDER BY id DESC LIMIT 20
                """, (rs, row) -> rs.getString("created_at") + " " + rs.getString("alert_type") + " "
                + rs.getString("message"));
        return new OverviewView(currentDays, policy.configVersion(), policy.today().toString(),
                policy.prebuildThrough().toString(), runs, alerts);
    }

    @Transactional
    public PreviewView preview(UserAccount actor, int proposedDays) {
        policy.refresh();
        int currentDays = policy.retentionDays();
        TrackRetentionProperties properties = policy.properties();
        if (proposedDays < properties.minRetentionDays() || proposedDays > properties.maxRetentionDays()) {
            throw new BadRequestException("RETENTION_DAYS_OUT_OF_RANGE", "保留天数必须在 " + properties.minRetentionDays() + " 到 "
                    + properties.maxRetentionDays() + " 之间");
        }
        if (proposedDays >= currentDays) {
            throw new BadRequestException("RETENTION_NOT_SHORTENING", "仅支持缩短保留期，当前保留 " + currentDays + " 天");
        }
        LocalDate today = policy.today();
        LocalDate cutoff = today.minusDays(proposedDays - 1L);
        List<String> partitions = new ArrayList<>();
        long estimatedRows = 0;
        for (TrackPartition partition : catalog.list()) {
            if (partition.day() != null && partition.day().isBefore(cutoff)) {
                partitions.add(partition.name());
                estimatedRows += partition.estimatedRows();
            }
        }
        partitions.sort(String::compareTo);
        String token = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = now.plusMinutes(PREVIEW_TTL_MINUTES);
        jdbc.update("""
                INSERT INTO track_retention_change (token, current_days, proposed_days, current_version,
                    cutoff_date, partitions_json, estimated_rows, status, created_by, created_by_name,
                    created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PREVIEWED', ?, ?, ?, ?)
                """, token, currentDays, proposedDays, policy.configVersion(), cutoff,
                String.join(",", partitions), estimatedRows, actor.getId(), actor.getUsername(), now, expiresAt);
        return new PreviewView(token, currentDays, proposedDays, cutoff, partitions, estimatedRows, expiresAt);
    }

    @Transactional
    public ConfirmView confirm(UserAccount actor, String token) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM track_retention_change WHERE token = ? FOR UPDATE", token);
        if (rows.isEmpty()) {
            throw new NotFoundException("RETENTION_PREVIEW_NOT_FOUND", "保留期变更预览不存在");
        }
        Map<String, Object> change = rows.getFirst();
        String status = (String) change.get("status");
        if (!"PREVIEWED".equals(status)) {
            throw new ConflictException("RETENTION_PREVIEW_PROCESSED", "该预览已处理，状态为 " + status);
        }
        LocalDateTime expiresAt = toLocalDateTime(change.get("expires_at"));
        if (LocalDateTime.now(clock).isAfter(expiresAt)) {
            jdbc.update("UPDATE track_retention_change SET status = 'EXPIRED' WHERE token = ?", token);
            throw new ConflictException("RETENTION_PREVIEW_EXPIRED", "该预览已过期，请重新预览");
        }
        int currentDays = ((Number) change.get("current_days")).intValue();
        int proposedDays = ((Number) change.get("proposed_days")).intValue();
        long previewedVersion = ((Number) change.get("current_version")).longValue();
        long actualVersion = policy.configVersion();
        if (previewedVersion != actualVersion) {
            jdbc.update("UPDATE track_retention_change SET status = 'FAILED' WHERE token = ?", token);
            auditService.record(CATEGORY, "RETENTION_SHORTEN", TrackRetentionPolicy.CONFIG_KEY,
                    AuditService.RESULT_FAILURE, actor.getId(), actor.getUsername(), null,
                    currentDays + " -> " + proposedDays + "; 配置版本已变化 " + previewedVersion
                            + " -> " + actualVersion);
            throw new ConflictException("RETENTION_CONFIG_CHANGED", "保留期已被他人修改，请重新预览");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("""
                UPDATE system_config SET config_value = ?, version = version + 1,
                    updated_by = ?, updated_at = ? WHERE config_key = ?
                """, String.valueOf(proposedDays), actor.getId(), now, TrackRetentionPolicy.CONFIG_KEY);
        policy.refresh();
        TrackPartitionMaintenance.MaintenanceResult result = maintenance.run("CONFIRM");
        if (!result.success()) {
            jdbc.update("UPDATE track_retention_change SET status = 'FAILED' WHERE token = ?", token);
            auditService.record(CATEGORY, "RETENTION_SHORTEN", TrackRetentionPolicy.CONFIG_KEY,
                    AuditService.RESULT_FAILURE, actor.getId(), actor.getUsername(), null,
                    currentDays + " -> " + proposedDays + "; 分区维护失败: " + result.message());
            throw new ConflictException("RETENTION_MAINTENANCE_FAILED", "保留期已更新但分区清理失败：" + result.message());
        }
        jdbc.update("UPDATE track_retention_change SET status = 'CONFIRMED', confirmed_at = ? WHERE token = ?",
                now, token);
        auditService.record(CATEGORY, "RETENTION_SHORTEN", TrackRetentionPolicy.CONFIG_KEY,
                AuditService.RESULT_SUCCESS, actor.getId(), actor.getUsername(), null,
                currentDays + " -> " + proposedDays + "; dropped=" + String.join(",", result.droppedPartitions()));
        return new ConfirmView(currentDays, proposedDays, toLocalDate(change.get("cutoff_date")),
                result.droppedPartitions(), result.droppedRowsEstimate(), true, null);
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        throw new IllegalStateException("无法解析时间列: " + value);
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        throw new IllegalStateException("无法解析日期列: " + value);
    }

    private static String value(String value) {
        return value == null ? "-" : value;
    }

    public record OverviewView(int retentionDays, long configVersion, String today,
                               String prebuildThrough, List<String> maintenanceRuns, List<String> alerts) {
    }

    public record PreviewView(String token, int currentDays, int proposedDays, LocalDate cutoffDate,
                              List<String> partitions, long estimatedRows, LocalDateTime expiresAt) {
    }

    public record ConfirmView(int previousDays, int retentionDays, LocalDate cutoffDate,
                              List<String> droppedPartitions, long droppedRowsEstimate,
                              boolean success, String message) {
    }
}
