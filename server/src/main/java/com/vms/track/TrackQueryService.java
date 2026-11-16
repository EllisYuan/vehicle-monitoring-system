package com.vms.track;

import com.vms.audit.AuditService;
import com.vms.identity.access.FleetScope;
import com.vms.identity.access.FleetScopeResolver;
import com.vms.identity.access.FleetScopeSql;
import com.vms.identity.account.UserAccount;
import com.vms.identity.security.ForbiddenException;
import com.vms.infrastructure.ClockConfig;
import com.vms.infrastructure.web.BadRequestException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 单车轨迹查询：先按无效定位与 >30 秒间隔分段，再在段内做 Douglas-Peucker 米制抽稀，
 * 首尾、分段边界与事件关联点强制保留，展示点总数不超过 2000；必要点超限时要求缩小范围。
 */
@Service
public class TrackQueryService {

    public static final int MAX_DISPLAY_POINTS = 2000;
    public static final long MAX_WINDOW_SECONDS = 24 * 3600L;
    public static final long SEGMENT_GAP_SECONDS = 30L;
    public static final double INITIAL_TOLERANCE_METERS = 10.0;
    public static final String GAP_INVALID_FIX = "INVALID_FIX";
    public static final String GAP_TIME_GAP = "TIME_GAP";
    private static final String AUDIT_CATEGORY = "TRACK";

    private final JdbcTemplate jdbc;
    private final FleetScopeResolver scopes;
    private final TrackRetentionPolicy retention;
    private final TrackPartitionCatalog partitions;
    private final AuditService audit;

    public TrackQueryService(JdbcTemplate jdbc, FleetScopeResolver scopes, TrackRetentionPolicy retention,
                             TrackPartitionCatalog partitions, AuditService audit) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.retention = retention;
        this.partitions = partitions;
        this.audit = audit;
    }

    public TrackView query(UserAccount user, long vehicleId, OffsetDateTime from, OffsetDateTime to) {
        LocalDateTime fromLocal = businessTime(from);
        LocalDateTime toLocal = businessTime(to);
        String range = "from=" + fromLocal + ", to=" + toLocal;
        if (fromLocal.isAfter(toLocal)) {
            throw rejected(user, vehicleId, range, "TRACK_WINDOW_INVALID", "起始时间不能晚于结束时间");
        }
        if (Duration.between(fromLocal, toLocal).getSeconds() > MAX_WINDOW_SECONDS) {
            throw rejected(user, vehicleId, range, "TRACK_WINDOW_EXCEEDS_24H", "单次轨迹查询时间范围不能超过 24 小时");
        }
        LocalDate fromDay = fromLocal.toLocalDate();
        LocalDate toDay = toLocal.toLocalDate();
        if (fromDay.isBefore(retention.earliestRetainedDate()) || toDay.isAfter(retention.prebuildThrough())) {
            throw rejected(user, vehicleId, range, "TRACK_OUTSIDE_RETENTION", "查询范围超出轨迹保留期");
        }

        List<Map<String, Object>> vehicles = jdbc.queryForList(
                "SELECT id, plate_no, fleet_id FROM vehicle WHERE id = ?", vehicleId);
        if (vehicles.isEmpty()) {
            throw rejected(user, vehicleId, range, "VEHICLE_NOT_FOUND", "车辆不存在", false);
        }
        Map<String, Object> vehicle = vehicles.getFirst();
        String plateNo = (String) vehicle.get("plate_no");
        long currentFleetId = ((Number) vehicle.get("fleet_id")).longValue();
        FleetScope scope = scopes.resolve(user);
        if (!scope.contains(currentFleetId)) {
            record(user, vehicleId, AuditService.RESULT_FAILURE, range + ", reason=FORBIDDEN");
            throw new ForbiddenException();
        }

        List<RawPoint> rows = load(vehicleId, fromLocal, toLocal, fromDay, toDay, scope);
        Segmented segmented = segment(rows);
        List<List<RawPoint>> simplified = simplifyAll(segmented.segments(), user, vehicleId, range);
        int displayCount = simplified.stream().mapToInt(List::size).sum();

        List<Segment> segments = new ArrayList<>();
        for (List<RawPoint> points : simplified) {
            List<Point> viewPoints = points.stream().map(point -> new Point(offset(point.gpsTime()),
                    point.lng(), point.lat(), point.speedKph(), point.directionDeg(), point.eventRelated())).toList();
            segments.add(new Segment(offset(points.getFirst().gpsTime()), offset(points.getLast().gpsTime()),
                    viewPoints));
        }
        List<Gap> gaps = segmented.gaps().stream()
                .map(gap -> new Gap(offset(gap.startTime()), offset(gap.endTime()), gap.reason())).toList();
        TrackView view = new TrackView(vehicleId, plateNo, currentFleetId, offset(fromLocal), offset(toLocal),
                rows.size(), segmented.invalidPointCount(), displayCount, segments, gaps);
        record(user, vehicleId, AuditService.RESULT_SUCCESS, range + ", rawPoints=" + rows.size()
                + ", invalidPoints=" + segmented.invalidPointCount() + ", segments=" + segments.size()
                + ", displayPoints=" + displayCount);
        return view;
    }

    private List<RawPoint> load(long vehicleId, LocalDateTime from, LocalDateTime to,
                                LocalDate fromDay, LocalDate toDay, FleetScope scope) {
        List<String> names = partitions.list().stream()
                .filter(partition -> partition.day() != null)
                .filter(partition -> !partition.day().isBefore(fromDay) && !partition.day().isAfter(toDay))
                .sorted(Comparator.comparing(TrackPartition::day))
                .map(TrackPartition::name)
                .toList();
        if (names.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        args.add(vehicleId);
        args.add(from);
        args.add(to);
        String scopeWhere = FleetScopeSql.where("fleet_id_at_event", scope, args);
        String sql = "SELECT gps_time, lng, lat, speed, direction, alarm_flag, locate_state FROM track_point PARTITION ("
                + String.join(",", names) + ") WHERE vehicle_id = ? AND gps_time >= ? AND gps_time <= ? AND "
                + scopeWhere + " ORDER BY gps_time, id";
        return jdbc.query(sql, (rs, row) -> {
            BigDecimal lng = rs.getBigDecimal("lng");
            BigDecimal lat = rs.getBigDecimal("lat");
            String state = rs.getString("locate_state");
            boolean valid = "VALID".equals(state) && lng != null && lat != null;
            return new RawPoint(rs.getObject("gps_time", LocalDateTime.class),
                    lng == null ? null : lng.doubleValue(), lat == null ? null : lat.doubleValue(),
                    rs.getBigDecimal("speed").doubleValue(), rs.getInt("direction"),
                    rs.getLong("alarm_flag") != 0, valid);
        }, args.toArray());
    }

    private Segmented segment(List<RawPoint> rows) {
        List<List<RawPoint>> segments = new ArrayList<>();
        List<GapSpan> gaps = new ArrayList<>();
        List<RawPoint> current = new ArrayList<>();
        LocalDateTime previousEnd = null;
        String pendingReason = null;
        int invalid = 0;
        for (RawPoint point : rows) {
            if (!point.valid()) {
                invalid++;
                if (!current.isEmpty()) {
                    previousEnd = finish(segments, current);
                    current = new ArrayList<>();
                    pendingReason = GAP_INVALID_FIX;
                } else if (previousEnd != null) {
                    pendingReason = GAP_INVALID_FIX;
                }
                continue;
            }
            if (current.isEmpty()) {
                if (previousEnd != null) {
                    gaps.add(new GapSpan(previousEnd, point.gpsTime(),
                            pendingReason == null ? GAP_TIME_GAP : pendingReason));
                    pendingReason = null;
                }
                current.add(point);
            } else if (Duration.between(current.getLast().gpsTime(), point.gpsTime()).getSeconds()
                    > SEGMENT_GAP_SECONDS) {
                previousEnd = finish(segments, current);
                current = new ArrayList<>();
                gaps.add(new GapSpan(previousEnd, point.gpsTime(),
                        pendingReason == null ? GAP_TIME_GAP : pendingReason));
                pendingReason = null;
                current.add(point);
            } else {
                current.add(point);
            }
        }
        if (!current.isEmpty()) {
            finish(segments, current);
        }
        return new Segmented(segments, gaps, invalid);
    }

    private LocalDateTime finish(List<List<RawPoint>> segments, List<RawPoint> current) {
        segments.add(List.copyOf(current));
        return current.getLast().gpsTime();
    }

    private List<List<RawPoint>> simplifyAll(List<List<RawPoint>> segments, UserAccount user,
                                             long vehicleId, String range) {
        int mandatory = segments.stream().mapToInt(TrackQueryService::mandatoryCount).sum();
        if (mandatory > MAX_DISPLAY_POINTS) {
            throw tooDense(user, vehicleId, range, mandatory);
        }
        double tolerance = INITIAL_TOLERANCE_METERS;
        while (true) {
            List<List<RawPoint>> simplified = new ArrayList<>();
            int count = 0;
            for (List<RawPoint> segment : segments) {
                List<RawPoint> result = simplify(segment, tolerance);
                simplified.add(result);
                count += result.size();
            }
            if (count <= MAX_DISPLAY_POINTS) {
                return simplified;
            }
            tolerance *= 2;
        }
    }

    private static int mandatoryCount(List<RawPoint> points) {
        if (points.isEmpty()) {
            return 0;
        }
        int count = points.size() == 1 ? 1 : 2;
        for (int i = 1; i < points.size() - 1; i++) {
            if (points.get(i).eventRelated()) {
                count++;
            }
        }
        return count;
    }

    private static List<RawPoint> simplify(List<RawPoint> points, double tolerance) {
        int size = points.size();
        if (size <= 2) {
            return List.copyOf(points);
        }
        List<Integer> anchors = new ArrayList<>();
        anchors.add(0);
        for (int i = 1; i < size - 1; i++) {
            if (points.get(i).eventRelated()) {
                anchors.add(i);
            }
        }
        anchors.add(size - 1);
        boolean[] keep = new boolean[size];
        for (int index : anchors) {
            keep[index] = true;
        }
        for (int i = 0; i < anchors.size() - 1; i++) {
            douglasPeucker(points, anchors.get(i), anchors.get(i + 1), tolerance, keep);
        }
        List<RawPoint> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (keep[i]) {
                result.add(points.get(i));
            }
        }
        return result;
    }

    private static void douglasPeucker(List<RawPoint> points, int first, int last,
                                       double tolerance, boolean[] keep) {
        if (last <= first + 1) {
            return;
        }
        double maxDistance = -1;
        int farthest = -1;
        for (int i = first + 1; i < last; i++) {
            double distance = perpendicularMeters(points.get(i), points.get(first), points.get(last));
            if (distance > maxDistance) {
                maxDistance = distance;
                farthest = i;
            }
        }
        if (maxDistance > tolerance) {
            keep[farthest] = true;
            douglasPeucker(points, first, farthest, tolerance, keep);
            douglasPeucker(points, farthest, last, tolerance, keep);
        }
    }

    /** 小范围内用等距圆柱投影近似米制距离，10m 级抽稀精度足够。 */
    private static double perpendicularMeters(RawPoint point, RawPoint start, RawPoint end) {
        double reference = Math.toRadians((start.lat() + end.lat()) / 2);
        double scaleX = 111_320.0 * Math.cos(reference);
        double scaleY = 110_540.0;
        double baseX = (end.lng() - start.lng()) * scaleX;
        double baseY = (end.lat() - start.lat()) * scaleY;
        double pointX = (point.lng() - start.lng()) * scaleX;
        double pointY = (point.lat() - start.lat()) * scaleY;
        double lengthSquared = baseX * baseX + baseY * baseY;
        if (lengthSquared == 0) {
            return Math.hypot(pointX, pointY);
        }
        double ratio = Math.max(0, Math.min(1, (pointX * baseX + pointY * baseY) / lengthSquared));
        return Math.hypot(pointX - ratio * baseX, pointY - ratio * baseY);
    }

    private RuntimeException tooDense(UserAccount user, long vehicleId, String range, int mandatory) {
        record(user, vehicleId, AuditService.RESULT_FAILURE,
                range + ", reason=TRACK_TOO_DENSE, mandatoryPoints=" + mandatory);
        return new BadRequestException("TRACK_TOO_DENSE", "轨迹必要点超过 2000 个，请缩小时间范围");
    }

    private RuntimeException rejected(UserAccount user, long vehicleId, String range,
                                      String code, String message) {
        return rejected(user, vehicleId, range, code, message, true);
    }

    private RuntimeException rejected(UserAccount user, long vehicleId, String range,
                                      String code, String message, boolean auditFailure) {
        if (auditFailure) {
            record(user, vehicleId, AuditService.RESULT_FAILURE, range + ", reason=" + code);
        }
        return new BadRequestException(code, message);
    }

    private void record(UserAccount user, long vehicleId, String result, String detail) {
        audit.record(AUDIT_CATEGORY, "QUERY", "vehicle:" + vehicleId, result,
                user.getId(), user.getUsername(), null, detail);
    }

    private static LocalDateTime businessTime(OffsetDateTime value) {
        return value.atZoneSameInstant(ClockConfig.BUSINESS_ZONE).toLocalDateTime();
    }

    private static OffsetDateTime offset(LocalDateTime value) {
        return value.atZone(ClockConfig.BUSINESS_ZONE).toOffsetDateTime();
    }

    private record RawPoint(LocalDateTime gpsTime, Double lng, Double lat, double speedKph,
                            int directionDeg, boolean eventRelated, boolean valid) { }

    private record GapSpan(LocalDateTime startTime, LocalDateTime endTime, String reason) { }

    private record Segmented(List<List<RawPoint>> segments, List<GapSpan> gaps, int invalidPointCount) { }

    public record TrackView(long vehicleId, String plateNo, long fleetId, OffsetDateTime from, OffsetDateTime to,
                            int rawPointCount, int invalidPointCount, int displayPointCount,
                            List<Segment> segments, List<Gap> gaps) { }

    public record Segment(OffsetDateTime startTime, OffsetDateTime endTime, List<Point> points) { }

    public record Point(OffsetDateTime gpsTime, double lng, double lat, double speedKph,
                        int directionDeg, boolean eventRelated) { }

    public record Gap(OffsetDateTime startTime, OffsetDateTime endTime, String reason) { }
}


