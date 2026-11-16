package com.vms.audit;

import com.vms.infrastructure.web.BadRequestException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 只读审计与运行记录查询：按前序票实际写入的 audit_log / notification_outbox 字段读取，
 * 不重定义写入格式，也不补写历史。查询始终带时间窗口、分页大小上限与偏移上限。
 */
@Service
public class AuditQueryService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_WINDOW_DAYS = 7;
    public static final int MAX_WINDOW_DAYS = 31;
    public static final int MAX_OFFSET = 10_000;
    public static final int MAX_DEAD_OUTBOX_ROWS = 100;

    private static final List<String> OUTBOX_STATUSES =
            List.of("PENDING", "RETRY", "DONE", "DEAD");

    /** 键值形式的凭证：password/pwd、鉴权码、token、WS ticket 等一律替换为 ***。 */
    private static final Pattern CREDENTIAL_PAIR = Pattern.compile(
            "(?i)((?<![A-Za-z0-9_])(?:new_?password|old_?password|password|passwd|pwd"
                    + "|auth_?code|auth_?secret|ws_?ticket|satoken|token|ticket|secret|credential)"
                    + "\\s*\"?\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^,;&}\\]\\s]+)");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuditQueryService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public AuditPage search(String actor, String category, String action, String result,
                            LocalDateTime from, LocalDateTime to, int page, int size) {
        validatePaging(page, size);
        LocalDateTime resolvedTo = to != null ? to : LocalDateTime.now(clock);
        LocalDateTime resolvedFrom = from != null ? from : resolvedTo.minusDays(DEFAULT_WINDOW_DAYS);
        validateWindow(resolvedFrom, resolvedTo);
        return query(resolvedFrom, resolvedTo, actor, category, action, result, page, size);
    }

    private AuditPage query(LocalDateTime from, LocalDateTime to, String actor, String category,
                            String action, String result, int page, int size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE created_at >= ? AND created_at < ?");
        args.add(from);
        args.add(to);
        appendEquals(where, args, "actor_name", normalized(actor, 64, "actor"));
        appendEquals(where, args, "category", normalized(category, 32, "category"));
        appendEquals(where, args, "action", normalized(action, 64, "action"));
        appendEquals(where, args, "result", normalized(result, 16, "result"));

        Long counted = jdbc.queryForObject("SELECT count(*) FROM audit_log" + where, Long.class, args.toArray());
        long total = counted == null ? 0 : counted;
        args.add(size);
        args.add(page * size);
        List<AuditEntry> items = jdbc.query("""
                SELECT id, actor_id, actor_name, category, action, target, result, source_ip, detail, created_at
                FROM audit_log
                """ + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                AuditQueryService::map, args.toArray());
        return new AuditPage(items, page, size, total, (long) (page + 1) * size < total);
    }

    /**
     * 运行记录只读取已实现的源事实：notification_outbox 各状态计数与 DEAD 明细。
     * 分区维护告警由后续维护票实现后再在此补充，不用占位数据冒充。
     */
    public OperationRecords operationRecords() {
        Map<String, Long> counts = new LinkedHashMap<>();
        OUTBOX_STATUSES.forEach(status -> counts.put(status, 0L));
        jdbc.query("SELECT status, count(*) AS total FROM notification_outbox GROUP BY status",
                rs -> {
                    counts.put(rs.getString("status"), rs.getLong("total"));
                });
        List<OutboxDeadEntry> dead = jdbc.query("""
                SELECT event_id, event_type, aggregate_id, attempts, retry_at, updated_at
                FROM notification_outbox
                WHERE status = 'DEAD'
                ORDER BY updated_at DESC, event_id
                LIMIT ?
                """, (rs, row) -> new OutboxDeadEntry(
                        rs.getString("event_id"), rs.getString("event_type"),
                        rs.getLong("aggregate_id"), rs.getInt("attempts"),
                        rs.getObject("retry_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class)), MAX_DEAD_OUTBOX_ROWS);
        return new OperationRecords(dead, counts, LocalDateTime.now(clock));
    }

    private static void appendEquals(StringBuilder where, List<Object> args, String column, String value) {
        if (value != null) {
            where.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    private static String normalized(String value, int maxLength, String name) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new BadRequestException("INVALID_AUDIT_FILTER", "筛选条件 " + name + " 过长");
        }
        return trimmed;
    }

    private static void validateWindow(LocalDateTime from, LocalDateTime to) {
        if (!from.isBefore(to)) {
            throw new BadRequestException("INVALID_TIME_RANGE", "开始时间必须早于结束时间");
        }
        if (from.isBefore(to.minusDays(MAX_WINDOW_DAYS))) {
            throw new BadRequestException("TIME_RANGE_TOO_WIDE",
                    "时间范围不能超过 " + MAX_WINDOW_DAYS + " 天");
        }
    }

    private static void validatePaging(int page, int size) {
        if (page < 0) {
            throw new BadRequestException("INVALID_PAGE", "页码不能小于 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("INVALID_PAGE_SIZE", "每页条数必须在 1 到 " + MAX_PAGE_SIZE + " 之间");
        }
        if ((long) page * size > MAX_OFFSET) {
            throw new BadRequestException("PAGE_OFFSET_TOO_LARGE", "请缩小筛选范围后重试");
        }
    }

    static String maskDetail(String detail) {
        if (detail == null) {
            return null;
        }
        Matcher matcher = CREDENTIAL_PAIR.matcher(detail);
        return matcher.replaceAll("$1***");
    }

    private static AuditEntry map(ResultSet rs, int row) throws SQLException {
        return new AuditEntry(rs.getLong("id"), rs.getObject("actor_id", Long.class),
                rs.getString("actor_name"), rs.getString("category"), rs.getString("action"),
                rs.getString("target"), rs.getString("result"), rs.getString("source_ip"),
                maskDetail(rs.getString("detail")),
                rs.getObject("created_at", LocalDateTime.class));
    }

    public record AuditPage(List<AuditEntry> items, int page, int size, long total, boolean hasMore) { }

    public record AuditEntry(long id, Long actorId, String actorName, String category, String action,
                             String target, String result, String sourceIp, String detail,
                             LocalDateTime createdAt) { }

    public record OperationRecords(List<OutboxDeadEntry> deadOutbox, Map<String, Long> outboxCounts,
                                   LocalDateTime generatedAt) { }

    public record OutboxDeadEntry(String eventId, String eventType, long aggregateId, int attempts,
                                  LocalDateTime retryAt, LocalDateTime updatedAt) { }
}
