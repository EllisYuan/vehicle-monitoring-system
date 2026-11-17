package com.vms.statistics;

import com.vms.access.TerminalAccessPort;
import com.vms.identity.access.FleetScope;
import com.vms.identity.access.FleetScopeResolver;
import com.vms.identity.access.FleetScopeSql;
import com.vms.identity.account.UserAccount;
import com.vms.identity.security.ForbiddenException;
import com.vms.infrastructure.ClockConfig;
import com.vms.infrastructure.EffectiveTime;
import com.vms.infrastructure.web.BadRequestException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 19：有权限的监控统计。所有视图共用同一份报警批次查询与车辆状态分类，
 * 历史报警按 fleet_id_at_event 归属，车辆当前状态按当前档案归属，权限先于统计过滤。
 */
@Service
public class StatisticsService {

    static final String TIMEZONE = ClockConfig.BUSINESS_ZONE.getId();
    private static final int OFFLINE_THRESHOLD_SECONDS = 180;
    private static final int MAX_RANGE_DAYS = 90;
    private static final DateTimeFormatter WINDOW_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final JdbcTemplate jdbc;
    private final FleetScopeResolver scopes;
    private final TerminalAccessPort access;
    private final Clock clock;
    private final int startupGraceSeconds;
    private final LocalDateTime startedAt;

    public StatisticsService(JdbcTemplate jdbc, FleetScopeResolver scopes, TerminalAccessPort access,
                             Clock clock, StatisticsProperties properties) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.access = access;
        this.clock = clock;
        this.startupGraceSeconds = properties.startupGraceSeconds();
        this.startedAt = EffectiveTime.now(clock);
    }

    public Overview overview(UserAccount user, Long fleetId) {
        FleetScope scope = scopeOf(user, fleetId);
        LocalDateTime now = EffectiveTime.now(clock);
        LocalDate today = now.toLocalDate();
        Range range = new Range(today, today, today.atStartOfDay(), now);
        return new Overview(TIMEZONE, today, format(range.from()), format(now),
                sumVehicles(vehicleStates(scope, fleetId, now)),
                alarmMetrics(alarmBuckets(scope, fleetId, range)));
    }

    public Trend trend(UserAccount user, LocalDate startDate, LocalDate endDate, Long fleetId) {
        FleetScope scope = scopeOf(user, fleetId);
        Range range = resolveRange(startDate, endDate, 7);
        Map<LocalDate, List<AlarmBucket>> byDay = alarmBuckets(scope, fleetId, range).stream()
                .collect(Collectors.groupingBy(AlarmBucket::day));
        List<TrendPoint> days = new ArrayList<>();
        for (LocalDate date = range.startDate(); !date.isAfter(range.endDate()); date = date.plusDays(1)) {
            days.add(new TrendPoint(date, alarmMetrics(byDay.getOrDefault(date, List.of()))));
        }
        return new Trend(TIMEZONE, range.startDate(), range.endDate(), days);
    }

    public TypeDistribution types(UserAccount user, LocalDate startDate, LocalDate endDate, Long fleetId) {
        FleetScope scope = scopeOf(user, fleetId);
        Range range = resolveRange(startDate, endDate, 1);
        Map<String, List<AlarmBucket>> byType = alarmBuckets(scope, fleetId, range).stream()
                .collect(Collectors.groupingBy(AlarmBucket::alarmType, TreeMap::new, Collectors.toList()));
        List<TypeStat> types = byType.entrySet().stream()
                .map(entry -> new TypeStat(entry.getKey(), alarmMetrics(entry.getValue())))
                .toList();
        long total = types.stream().mapToLong(stat -> stat.alarms().total()).sum();
        return new TypeDistribution(TIMEZONE, range.startDate(), range.endDate(), total, types);
    }

    public FleetStats fleets(UserAccount user, LocalDate startDate, LocalDate endDate, Long fleetId) {
        FleetScope scope = scopeOf(user, fleetId);
        Range range = resolveRange(startDate, endDate, 1);
        LocalDateTime now = EffectiveTime.now(clock);
        List<VehicleState> vehicles = vehicleStates(scope, fleetId, now);
        List<AlarmBucket> alarms = alarmBuckets(scope, fleetId, range);
        Map<Long, List<VehicleState>> vehiclesByFleet = vehicles.stream()
                .collect(Collectors.groupingBy(VehicleState::fleetId));
        Map<Long, List<AlarmBucket>> alarmsByFleet = alarms.stream()
                .collect(Collectors.groupingBy(AlarmBucket::fleetId));
        List<FleetStat> rows = new ArrayList<>();
        for (FleetRow fleet : fleetRows(scope, fleetId)) {
            rows.add(new FleetStat(fleet.id(), fleet.code(), fleet.name(), fleet.status(),
                    sumVehicles(vehiclesByFleet.getOrDefault(fleet.id(), List.of())),
                    alarmMetrics(alarmsByFleet.getOrDefault(fleet.id(), List.of()))));
        }
        return new FleetStats(TIMEZONE, range.startDate(), range.endDate(),
                sumVehicles(vehicles), alarmMetrics(alarms), rows);
    }

    private FleetScope scopeOf(UserAccount user, Long fleetId) {
        FleetScope scope = scopes.resolve(user);
        if (fleetId != null && !scope.contains(fleetId)) {
            throw new ForbiddenException();
        }
        return scope;
    }

    private Range resolveRange(LocalDate startDate, LocalDate endDate, int defaultSpanDays) {
        LocalDate today = LocalDate.now(clock);
        LocalDate end = endDate == null ? today : endDate;
        LocalDate start = startDate == null ? end.minusDays(defaultSpanDays - 1L) : startDate;
        if (end.isAfter(today)) {
            throw new BadRequestException("INVALID_DATE_RANGE", "统计结束日期不能晚于今天");
        }
        if (start.isAfter(end)) {
            throw new BadRequestException("INVALID_DATE_RANGE", "统计起始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw new BadRequestException("INVALID_DATE_RANGE", "统计区间最多 " + MAX_RANGE_DAYS + " 天");
        }
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime toInclusive = end.equals(today)
                ? EffectiveTime.now(clock)
                : end.plusDays(1).atStartOfDay().minus(1, ChronoUnit.MILLIS);
        return new Range(start, end, from, toInclusive);
    }

    private List<FleetRow> fleetRows(FleetScope scope, Long fleetId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id,fleet_code,fleet_name,status FROM fleet WHERE ");
        sql.append(FleetScopeSql.where("id", scope, args));
        if (fleetId != null) {
            sql.append(" AND id=?");
            args.add(fleetId);
        }
        sql.append(" ORDER BY fleet_code,id");
        return jdbc.query(sql.toString(), (rs, row) -> new FleetRow(rs.getLong("id"),
                rs.getString("fleet_code"), rs.getString("fleet_name"), rs.getString("status")), args.toArray());
    }

    private List<VehicleState> vehicleStates(FleetScope scope, Long fleetId, LocalDateTime now) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(FleetScopeSql.where("v.fleet_id", scope, args));
        if (fleetId != null) {
            where.append(" AND v.fleet_id=?");
            args.add(fleetId);
        }
        String sql = """
                SELECT v.id AS vehicle_id,v.fleet_id,v.status AS vehicle_status,
                       t.id AS terminal_id,t.terminal_no,t.status AS terminal_status,
                       t.last_seen_at,t.ever_authenticated
                FROM vehicle v
                LEFT JOIN terminal_vehicle_binding b ON b.current_vehicle_id=v.id
                LEFT JOIN terminal t ON t.id=b.terminal_id
                WHERE %s
                ORDER BY v.id
                """.formatted(where);
        return jdbc.query(sql, (rs, row) -> new VehicleState(rs.getLong("fleet_id"), classify(rs, now)),
                args.toArray());
    }

    /**
     * 与实时监控快照同口径：在线还要求进程内可写会话；离线只依据已提交的 last_seen_at；
     * 启动宽限内的“本应离线”车辆单列为启动待确认，不混入离线。
     */
    private String classify(ResultSet rs, LocalDateTime now) throws SQLException {
        Long terminalId = rs.getObject("terminal_id", Long.class);
        if (!"ENABLED".equals(rs.getString("vehicle_status"))
                || terminalId != null && !"ENABLED".equals(rs.getString("terminal_status"))) {
            return "DISABLED";
        }
        if (terminalId == null) {
            return "UNBOUND";
        }
        if (!rs.getBoolean("ever_authenticated")) {
            return "NOT_CONNECTED";
        }
        LocalDateTime lastSeenAt = rs.getObject("last_seen_at", LocalDateTime.class);
        boolean recentlySeen = lastSeenAt != null
                && !lastSeenAt.isBefore(now.minusSeconds(OFFLINE_THRESHOLD_SECONDS))
                && !lastSeenAt.isAfter(now);
        boolean writable = access.current(rs.getString("terminal_no"))
                .filter(TerminalAccessPort.SessionView::writable).isPresent();
        if (recentlySeen && writable) {
            return "ONLINE";
        }
        if (startupGraceSeconds > 0 && now.isBefore(startedAt.plusSeconds(startupGraceSeconds))) {
            return "STARTUP_PENDING";
        }
        return recentlySeen ? "ACTIVE_DISCONNECTED" : "OFFLINE";
    }

    private List<AlarmBucket> alarmBuckets(FleetScope scope, Long fleetId, Range range) {
        List<Object> args = new ArrayList<>();
        args.add(range.from());
        args.add(range.toInclusive());
        StringBuilder where = new StringBuilder(" WHERE a.start_time>=? AND a.start_time<=? AND ");
        where.append(FleetScopeSql.where("a.fleet_id_at_event", scope, args));
        if (fleetId != null) {
            where.append(" AND a.fleet_id_at_event=?");
            args.add(fleetId);
        }
        String sql = """
                SELECT DATE(a.start_time) AS alarm_day,a.fleet_id_at_event AS fleet_id,
                       a.alarm_type,a.handle_status,COUNT(*) AS total
                FROM alarm a
                """ + where + " GROUP BY alarm_day,a.fleet_id_at_event,a.alarm_type,a.handle_status"
                + " ORDER BY alarm_day,a.fleet_id_at_event,a.alarm_type,a.handle_status";
        return jdbc.query(sql, (rs, row) -> new AlarmBucket(rs.getDate("alarm_day").toLocalDate(),
                rs.getLong("fleet_id"), rs.getString("alarm_type"), rs.getString("handle_status"),
                rs.getLong("total")), args.toArray());
    }

    private VehicleMetrics sumVehicles(List<VehicleState> states) {
        long online = count(states, "ONLINE");
        long offline = count(states, "OFFLINE");
        long notConnected = count(states, "NOT_CONNECTED");
        long disabled = count(states, "DISABLED");
        long activeDisconnected = count(states, "ACTIVE_DISCONNECTED");
        long startupPending = count(states, "STARTUP_PENDING");
        long unbound = count(states, "UNBOUND");
        return new VehicleMetrics(states.size(), online, offline, notConnected, disabled,
                activeDisconnected, startupPending, unbound);
    }

    private long count(List<VehicleState> states, String communicationStatus) {
        return states.stream().filter(state -> communicationStatus.equals(state.communicationStatus())).count();
    }

    private AlarmMetrics alarmMetrics(List<AlarmBucket> buckets) {
        long total = 0;
        long pending = 0;
        long processing = 0;
        long handled = 0;
        long ignored = 0;
        for (AlarmBucket bucket : buckets) {
            total += bucket.count();
            switch (bucket.handleStatus()) {
                case "PENDING" -> pending += bucket.count();
                case "PROCESSING" -> processing += bucket.count();
                case "HANDLED" -> handled += bucket.count();
                case "IGNORED" -> ignored += bucket.count();
                default -> throw new IllegalStateException("未知处置状态：" + bucket.handleStatus());
            }
        }
        long numerator = handled + ignored;
        Double percent = total == 0 ? null : Math.round(numerator * 1000.0 / total) / 10.0;
        return new AlarmMetrics(total, pending, processing, handled, ignored,
                new HandlingRate(numerator, total, percent, "含误报"));
    }

    private String format(LocalDateTime value) {
        return value.atZone(ClockConfig.BUSINESS_ZONE).toOffsetDateTime().format(WINDOW_FORMAT);
    }

    private record Range(LocalDate startDate, LocalDate endDate, LocalDateTime from, LocalDateTime toInclusive) { }

    private record FleetRow(long id, String code, String name, String status) { }

    private record VehicleState(long fleetId, String communicationStatus) { }

    private record AlarmBucket(LocalDate day, long fleetId, String alarmType, String handleStatus, long count) { }

    public record Overview(String timezone, LocalDate today, String startTimeFrom, String startTimeTo,
                           VehicleMetrics vehicles, AlarmMetrics alarms) { }

    public record VehicleMetrics(long total, long online, long offline, long notConnected, long disabled,
                                 long activeDisconnected, long startupPending, long unbound) { }

    public record AlarmMetrics(long total, long pending, long processing, long handled, long ignored,
                               HandlingRate handlingRate) { }

    public record HandlingRate(long numerator, long denominator, Double percent, String note) { }

    public record Trend(String timezone, LocalDate startDate, LocalDate endDate, List<TrendPoint> days) { }

    public record TrendPoint(LocalDate date, AlarmMetrics alarms) { }

    public record TypeDistribution(String timezone, LocalDate startDate, LocalDate endDate, long total,
                                   List<TypeStat> types) { }

    public record TypeStat(String alarmType, AlarmMetrics alarms) { }

    public record FleetStats(String timezone, LocalDate startDate, LocalDate endDate,
                             VehicleMetrics totalVehicles, AlarmMetrics totalAlarms, List<FleetStat> fleets) { }

    public record FleetStat(long fleetId, String fleetCode, String fleetName, String status,
                            VehicleMetrics vehicles, AlarmMetrics alarms) { }
}
