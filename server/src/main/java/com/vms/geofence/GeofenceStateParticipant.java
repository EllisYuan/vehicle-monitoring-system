package com.vms.geofence;

import com.vms.audit.AuditService;
import com.vms.protocol.PositionReport;
import com.vms.telemetry.PositionStateParticipant;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 15：围栏进出判定，挂在 07 的位置事务内执行。
 *
 * <p>每车每围栏保存稳定内外基线与候选计数：首个有效实时点只建立基线；边界算内；
 * 连续 3 个有效实时点确认新状态后生成一条瞬态事件；相邻有效点间隔超过 30 秒候选重计；
 * 围栏形状或绑定变化（版本递增）后下一个实时点重建基线，且不推测期间漏掉的进出。
 * 围栏状态、事件行与 notification_outbox 与位置同事务提交；补传历史点不进入参与者。
 */
@Component
public class GeofenceStateParticipant implements PositionStateParticipant {

    static final String INSIDE = "INSIDE";
    static final String OUTSIDE = "OUTSIDE";
    static final Duration CANDIDATE_GAP = Duration.ofSeconds(30);
    static final int CONFIRM_POINTS = 3;
    static final double METRES_PER_DEGREE = 111_320.0;
    static final double EARTH_RADIUS_M = 6_371_008.8;
    static final double BOUNDARY_TOLERANCE_M = 0.01;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final Clock clock;

    public GeofenceStateParticipant(JdbcTemplate jdbc, ObjectMapper json, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public void apply(long vehicleId, long terminalId, long fleetId, long version,
                      PositionReport report, LocalDateTime receivedAt) {
        if (!report.fixValid()) {
            return;
        }
        List<Fence> fences = jdbc.query("""
                SELECT g.id, g.fence_name, g.fence_type, g.center_lng, g.center_lat, g.radius, g.vertices,
                       g.enter_enabled, g.leave_enabled, g.version
                FROM geofence g JOIN geofence_vehicle gv ON gv.geofence_id = g.id
                WHERE gv.vehicle_id = ? AND g.status = 'ENABLED'
                ORDER BY g.id
                """, this::fence, vehicleId);
        for (Fence fence : fences) {
            evaluate(vehicleId, terminalId, fleetId, report, fence);
        }
    }

    private void evaluate(long vehicleId, long terminalId, long fleetId, PositionReport report, Fence fence) {
        String state = contains(fence, report.longitude(), report.latitude()) ? INSIDE : OUTSIDE;
        LocalDateTime now = LocalDateTime.now(clock);
        List<StateRow> rows = jdbc.query("""
                SELECT fence_version, inside_state, candidate_state, consecutive_count, last_point_time
                FROM geofence_vehicle_state WHERE geofence_id = ? AND vehicle_id = ?
                FOR UPDATE
                """, (rs, row) -> new StateRow(rs.getLong("fence_version"), rs.getString("inside_state"),
                rs.getString("candidate_state"), rs.getInt("consecutive_count"),
                rs.getObject("last_point_time", LocalDateTime.class)), fence.id(), vehicleId);
        if (rows.isEmpty()) {
            jdbc.update("""
                    INSERT INTO geofence_vehicle_state (geofence_id, vehicle_id, fence_version, inside_state,
                        candidate_state, consecutive_count, last_point_time, updated_at)
                    VALUES (?, ?, ?, ?, NULL, 0, ?, ?)
                    """, fence.id(), vehicleId, fence.version(), state, report.time(), now);
            return;
        }
        StateRow row = rows.getFirst();
        if (row.fenceVersion() != fence.version()) {
            rebuildBaseline(vehicleId, report, fence, state, now);
            return;
        }
        if (!report.time().isAfter(row.lastPointTime())) {
            return;
        }
        boolean continuous = !Duration.between(row.lastPointTime(), report.time())
                .minus(CANDIDATE_GAP).isPositive();
        String inside = row.insideState();
        String candidate = row.candidateState();
        int count = row.consecutiveCount();
        if (state.equals(inside)) {
            candidate = null;
            count = 0;
        } else if (!continuous || !state.equals(candidate)) {
            candidate = state;
            count = 1;
        } else {
            count++;
        }
        if (!state.equals(inside) && count >= CONFIRM_POINTS) {
            if (INSIDE.equals(state) ? fence.enterEnabled() : fence.leaveEnabled()) {
                createEvent(vehicleId, terminalId, fleetId, report, fence, state, now);
            }
            inside = state;
            candidate = null;
            count = 0;
        }
        jdbc.update("""
                UPDATE geofence_vehicle_state
                SET inside_state = ?, candidate_state = ?, consecutive_count = ?, last_point_time = ?, updated_at = ?
                WHERE geofence_id = ? AND vehicle_id = ?
                """, inside, candidate, count, report.time(), now, fence.id(), vehicleId);
    }

    /** 形状或绑定变化后只重建基线，不推测变化期间发生的进出。 */
    private void rebuildBaseline(long vehicleId, PositionReport report, Fence fence, String state,
                                 LocalDateTime now) {
        jdbc.update("""
                UPDATE geofence_vehicle_state
                SET fence_version = ?, inside_state = ?, candidate_state = NULL, consecutive_count = 0,
                    last_point_time = ?, updated_at = ?
                WHERE geofence_id = ? AND vehicle_id = ?
                """, fence.version(), state, report.time(), now, fence.id(), vehicleId);
        audit.record(AuditService.CATEGORY_GEOFENCE, "BASELINE_REBUILD", "geofence:" + fence.id(),
                AuditService.RESULT_SUCCESS, null, "SYSTEM", null,
                "vehicle=" + vehicleId + ", fenceVersion=" + fence.version() + ", insideState=" + state);
    }

    private void createEvent(long vehicleId, long terminalId, long fleetId, PositionReport report,
                             Fence fence, String state, LocalDateTime now) {
        String alarmType = INSIDE.equals(state) ? "GEOFENCE_ENTER" : "GEOFENCE_LEAVE";
        String snapshot = json.writeValueAsString(new FenceSnapshot(fence.id(), fence.name(), fence.type(),
                state, fence.version()));
        Long driverId = currentDriver(vehicleId);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO alarm (vehicle_id, terminal_id, driver_id, fleet_id_at_event, source, alarm_type,
                        business_dimension, condition_state, handle_status, rule_version, rule_snapshot,
                        start_time, last_time, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'PLATFORM', ?, ?, 'EVENT', 'PENDING', ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, vehicleId);
            ps.setLong(2, terminalId);
            if (driverId == null) {
                ps.setNull(3, Types.BIGINT);
            } else {
                ps.setLong(3, driverId);
            }
            ps.setLong(4, fleetId);
            ps.setString(5, alarmType);
            ps.setString(6, "geofence:" + fence.id());
            ps.setLong(7, fence.version());
            ps.setString(8, snapshot);
            ps.setObject(9, report.time());
            ps.setObject(10, report.time());
            ps.setObject(11, now);
            ps.setObject(12, now);
            return ps;
        }, keys);
        long alarmId = keys.getKey().longValue();
        String direction = INSIDE.equals(state) ? "enter" : "leave";
        jdbc.update("""
                INSERT INTO notification_outbox (event_id, event_type, aggregate_id, version, status,
                    retry_at, attempts, updated_at)
                VALUES (?, 'ALARM_EVENT', ?, 1, 'PENDING', ?, 0, ?)
                """, "geofence:" + direction + ":" + alarmId, alarmId, now, now);
    }

    private Long currentDriver(long vehicleId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT driver_id FROM vehicle_driver_binding WHERE current_vehicle_id = ?",
                Long.class, vehicleId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Fence fence(ResultSet rs, int row) throws SQLException {
        double centerLng = rs.getDouble("center_lng");
        boolean centerLngNull = rs.wasNull();
        double centerLat = rs.getDouble("center_lat");
        boolean centerLatNull = rs.wasNull();
        double radius = rs.getDouble("radius");
        boolean radiusNull = rs.wasNull();
        String vertices = rs.getString("vertices");
        List<Vertex> points = vertices == null ? List.of()
                : List.of(json.readValue(vertices, Vertex[].class));
        return new Fence(rs.getLong("id"), rs.getString("fence_name"), rs.getString("fence_type"),
                centerLngNull ? null : centerLng, centerLatNull ? null : centerLat,
                radiusNull ? null : radius, points, rs.getBoolean("enter_enabled"),
                rs.getBoolean("leave_enabled"), rs.getLong("version"));
    }

    /** 边界算内：圆按米制距离，多边形先判断到边的米制距离再按射线法。 */
    static boolean contains(Fence fence, double lng, double lat) {
        if ("CIRCLE".equals(fence.type())) {
            return haversineMeters(fence.centerLat(), fence.centerLng(), lat, lng) <= fence.radius();
        }
        List<Vertex> vertices = fence.vertices();
        boolean inside = false;
        for (int i = 0, j = vertices.size() - 1; i < vertices.size(); j = i++) {
            Vertex a = vertices.get(j);
            Vertex b = vertices.get(i);
            if (distanceToSegmentMeters(lng, lat, a, b) <= BOUNDARY_TOLERANCE_M) {
                return true;
            }
            boolean crosses = (b.lat() > lat) != (a.lat() > lat)
                    && lng < (a.lng() - b.lng()) * (lat - b.lat()) / (a.lat() - b.lat()) + b.lng();
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    private static double distanceToSegmentMeters(double lng, double lat, Vertex a, Vertex b) {
        double lngScale = METRES_PER_DEGREE * Math.cos(Math.toRadians(lat));
        double ax = (a.lng() - lng) * lngScale;
        double ay = (a.lat() - lat) * METRES_PER_DEGREE;
        double bx = (b.lng() - lng) * lngScale;
        double by = (b.lat() - lat) * METRES_PER_DEGREE;
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0 ? 0
                : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / lengthSquared));
        return Math.hypot(ax + t * dx, ay + t * dy);
    }

    record Vertex(double lng, double lat) { }

    record Fence(long id, String name, String type, Double centerLng, Double centerLat, Double radius,
                 List<Vertex> vertices, boolean enterEnabled, boolean leaveEnabled, long version) { }

    record StateRow(long fenceVersion, String insideState, String candidateState, int consecutiveCount,
                    LocalDateTime lastPointTime) { }

    record FenceSnapshot(long geofenceId, String fenceName, String fenceType, String insideState,
                         long fenceVersion) { }
}