package com.vms.geofence;

import com.vms.audit.AuditService;
import com.vms.geofence.GeofenceGeometryValidator.Geometry;
import com.vms.geofence.GeofenceGeometryValidator.Vertex;
import com.vms.identity.access.FleetScope;
import com.vms.identity.access.FleetScopeResolver;
import com.vms.identity.access.FleetScopeSql;
import com.vms.identity.account.UserAccount;
import com.vms.identity.security.ForbiddenException;
import com.vms.infrastructure.web.BadRequestException;
import com.vms.infrastructure.web.ConflictException;
import com.vms.infrastructure.web.NotFoundException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 15：围栏维护与车辆绑定。每次形状、启停或绑定变更都递增 geofence.version 并写 GEOFENCE 审计，
 * 已保存的判定基线因版本不一致在下一个实时点重建，不清理其他报警或处置记录。
 */
@Service
public class GeofenceAdminService {

    private static final String SELECT = """
            SELECT g.id, g.fence_name, g.fence_type, g.center_lng, g.center_lat, g.radius, g.vertices,
                   g.enter_enabled, g.leave_enabled, g.status, g.version, g.updated_at
            FROM geofence g
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FleetScopeResolver scopeResolver;
    private final AuditService auditService;
    private final Clock clock;

    public GeofenceAdminService(JdbcTemplate jdbc, ObjectMapper json, FleetScopeResolver scopeResolver,
                                AuditService auditService, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.scopeResolver = scopeResolver;
        this.auditService = auditService;
        this.clock = clock;
    }

    public List<GeofenceView> list(UserAccount user, Long fleetId) {
        FleetScope scope = scopeResolver.resolve(user);
        if (fleetId != null && !scope.contains(fleetId)) {
            throw new ForbiddenException();
        }
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE ");
        if (scope.all()) {
            sql.append("1=1");
        } else {
            sql.append("EXISTS (SELECT 1 FROM geofence_vehicle sgv JOIN vehicle sv ON sv.id = sgv.vehicle_id")
                    .append(" WHERE sgv.geofence_id = g.id AND ")
                    .append(FleetScopeSql.where("sv.fleet_id", scope, args)).append(")");
        }
        if (fleetId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM geofence_vehicle fgv JOIN vehicle fv ON fv.id = fgv.vehicle_id")
                    .append(" WHERE fgv.geofence_id = g.id AND fv.fleet_id = ?)");
            args.add(fleetId);
        }
        sql.append(" ORDER BY g.fence_name, g.id");
        List<FenceHeader> headers = jdbc.query(sql.toString(), this::header, args.toArray());
        List<GeofenceView> views = new ArrayList<>(headers.size());
        for (FenceHeader header : headers) {
            views.add(view(header, scope));
        }
        return views;
    }

    public GeofenceView get(UserAccount user, long id) {
        FleetScope scope = scopeResolver.resolve(user);
        List<Object> args = new ArrayList<>();
        args.add(id);
        String where = " WHERE g.id = ?";
        if (!scope.all()) {
            where += " AND EXISTS (SELECT 1 FROM geofence_vehicle sgv JOIN vehicle sv ON sv.id = sgv.vehicle_id"
                    + " WHERE sgv.geofence_id = g.id AND " + FleetScopeSql.where("sv.fleet_id", scope, args) + ")";
        }
        List<FenceHeader> headers = jdbc.query(SELECT + where, this::header, args.toArray());
        if (headers.isEmpty()) {
            if (!scope.all()) {
                throw new ForbiddenException();
            }
            throw new NotFoundException("GEOFENCE_NOT_FOUND", "围栏不存在");
        }
        return view(headers.getFirst(), scope);
    }

    @Transactional
    public GeofenceView create(UserAccount actor, GeofenceRequest request) {
        Geometry geometry = GeofenceGeometryValidator.validate(request.fenceType(), request.centerLng(),
                request.centerLat(), request.radiusM(), request.vertices());
        List<VehicleRow> vehicles = requireVehicles(actor, request.vehicleIds());
        LocalDateTime now = LocalDateTime.now(clock);
        long id;
        try {
            id = insert(geometry, request, now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("GEOFENCE_NAME_EXISTS", "围栏名称已存在");
        }
        replaceVehicles(id, vehicles, now);
        GeofenceView view = get(actor, id);
        audit(actor, "CREATE", id, "fenceType=" + geometry.fenceType() + ", version=" + view.version()
                + ", vehicleCount=" + vehicles.size());
        return view;
    }

    @Transactional
    public GeofenceView update(UserAccount actor, long id, GeofenceRequest request) {
        lock(id);
        Geometry geometry = GeofenceGeometryValidator.validate(request.fenceType(), request.centerLng(),
                request.centerLat(), request.radiusM(), request.vertices());
        List<VehicleRow> vehicles = request.vehicleIds() == null ? null : requireVehicles(actor, request.vehicleIds());
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            updateRow(id, geometry, request, now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("GEOFENCE_NAME_EXISTS", "围栏名称已存在");
        }
        if (vehicles != null) {
            replaceVehicles(id, vehicles, now);
        }
        GeofenceView view = get(actor, id);
        audit(actor, "UPDATE", id, "fenceType=" + geometry.fenceType() + ", version=" + view.version()
                + (vehicles == null ? "" : ", vehicleCount=" + vehicles.size()));
        return view;
    }

    @Transactional
    public GeofenceView updateStatus(UserAccount actor, long id, String status) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BadRequestException("INVALID_GEOFENCE_STATUS", "围栏状态必须为 ENABLED 或 DISABLED");
        }
        lock(id);
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("UPDATE geofence SET status = ?, version = version + 1, updated_at = ? WHERE id = ?",
                status, now, id);
        GeofenceView view = get(actor, id);
        audit(actor, "STATUS", id, "status=" + status + ", version=" + view.version());
        return view;
    }

    @Transactional
    public GeofenceView updateVehicles(UserAccount actor, long id, List<Long> vehicleIds) {
        lock(id);
        List<VehicleRow> vehicles = requireVehicles(actor, vehicleIds);
        LocalDateTime now = LocalDateTime.now(clock);
        replaceVehicles(id, vehicles, now);
        jdbc.update("UPDATE geofence SET version = version + 1, updated_at = ? WHERE id = ?", now, id);
        GeofenceView view = get(actor, id);
        audit(actor, "VEHICLES", id, "vehicleCount=" + vehicles.size() + ", version=" + view.version());
        return view;
    }

    private long insert(Geometry geometry, GeofenceRequest request, LocalDateTime now) {
        String verticesJson = geometry.vertices().isEmpty() ? null : json.writeValueAsString(geometry.vertices());
        String pointWkt = geometry.centerLng() == null ? null
                : String.format(Locale.ROOT, "POINT(%.7f %.7f)", geometry.centerLng(), geometry.centerLat());
        String boundaryWkt = geometry.vertices().isEmpty() ? null : polygonWkt(geometry.vertices());
        boolean enter = request.enterEnabled() == null || request.enterEnabled();
        boolean leave = request.leaveEnabled() == null || request.leaveEnabled();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO geofence (fence_name, fence_type, center_lng, center_lat, radius, vertices,
                        center_point, boundary, enter_enabled, leave_enabled, status, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?,
                        ST_GeomFromText(?, 4326, 'axis-order=long-lat'),
                        ST_GeomFromText(?, 4326, 'axis-order=long-lat'), ?, ?, 'ENABLED', 1, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, request.fenceName().strip());
            ps.setString(2, geometry.fenceType());
            setNullableDouble(ps, 3, geometry.centerLng());
            setNullableDouble(ps, 4, geometry.centerLat());
            setNullableDouble(ps, 5, geometry.radiusM());
            setNullableString(ps, 6, verticesJson);
            setNullableString(ps, 7, pointWkt);
            setNullableString(ps, 8, boundaryWkt);
            ps.setBoolean(9, enter);
            ps.setBoolean(10, leave);
            ps.setObject(11, now);
            ps.setObject(12, now);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private void updateRow(long id, Geometry geometry, GeofenceRequest request, LocalDateTime now) {
        String verticesJson = geometry.vertices().isEmpty() ? null : json.writeValueAsString(geometry.vertices());
        String pointWkt = geometry.centerLng() == null ? null
                : String.format(Locale.ROOT, "POINT(%.7f %.7f)", geometry.centerLng(), geometry.centerLat());
        String boundaryWkt = geometry.vertices().isEmpty() ? null : polygonWkt(geometry.vertices());
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    UPDATE geofence SET fence_name = ?, fence_type = ?, center_lng = ?, center_lat = ?,
                        radius = ?, vertices = ?,
                        center_point = ST_GeomFromText(?, 4326, 'axis-order=long-lat'),
                        boundary = ST_GeomFromText(?, 4326, 'axis-order=long-lat'),
                        enter_enabled = COALESCE(?, enter_enabled),
                        leave_enabled = COALESCE(?, leave_enabled),
                        version = version + 1, updated_at = ?
                    WHERE id = ?
                    """);
            ps.setString(1, request.fenceName().strip());
            ps.setString(2, geometry.fenceType());
            setNullableDouble(ps, 3, geometry.centerLng());
            setNullableDouble(ps, 4, geometry.centerLat());
            setNullableDouble(ps, 5, geometry.radiusM());
            setNullableString(ps, 6, verticesJson);
            setNullableString(ps, 7, pointWkt);
            setNullableString(ps, 8, boundaryWkt);
            setNullableBoolean(ps, 9, request.enterEnabled());
            setNullableBoolean(ps, 10, request.leaveEnabled());
            ps.setObject(11, now);
            ps.setLong(12, id);
            return ps;
        });
    }

    private void lock(long id) {
        if (jdbc.queryForList("SELECT id FROM geofence WHERE id = ? FOR UPDATE", Long.class, id).isEmpty()) {
            throw new NotFoundException("GEOFENCE_NOT_FOUND", "围栏不存在");
        }
    }

    private List<VehicleRow> requireVehicles(UserAccount actor, List<Long> vehicleIds) {
        if (vehicleIds == null) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : vehicleIds) {
            if (id == null) {
                throw new BadRequestException("INVALID_VEHICLE_IDS", "车辆 ID 不能为空");
            }
            ids.add(id);
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>(ids);
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<VehicleRow> rows = jdbc.query("""
                SELECT v.id, v.plate_no, v.fleet_id, f.fleet_name
                FROM vehicle v JOIN fleet f ON f.id = v.fleet_id
                WHERE v.id IN (""" + placeholders + ") ORDER BY v.id",
                (rs, row) -> new VehicleRow(rs.getLong("id"), rs.getString("plate_no"),
                        rs.getLong("fleet_id"), rs.getString("fleet_name")), args.toArray());
        if (rows.size() != ids.size()) {
            throw new NotFoundException("VEHICLE_NOT_FOUND", "车辆不存在");
        }
        FleetScope scope = scopeResolver.resolve(actor);
        for (VehicleRow row : rows) {
            if (!scope.contains(row.fleetId())) {
                throw new ForbiddenException();
            }
        }
        return rows;
    }

    private void replaceVehicles(long fenceId, List<VehicleRow> vehicles, LocalDateTime now) {
        jdbc.update("DELETE FROM geofence_vehicle WHERE geofence_id = ?", fenceId);
        for (VehicleRow vehicle : vehicles) {
            jdbc.update("INSERT INTO geofence_vehicle (geofence_id, vehicle_id, created_at) VALUES (?, ?, ?)",
                    fenceId, vehicle.id(), now);
        }
    }

    private List<VehicleRef> vehicles(long fenceId, FleetScope scope) {
        List<Object> args = new ArrayList<>();
        args.add(fenceId);
        String filter = "";
        if (!scope.all()) {
            filter = " AND " + FleetScopeSql.where("v.fleet_id", scope, args);
        }
        return jdbc.query("""
                SELECT v.id, v.plate_no, v.fleet_id, f.fleet_name
                FROM geofence_vehicle gv
                JOIN vehicle v ON v.id = gv.vehicle_id
                JOIN fleet f ON f.id = v.fleet_id
                WHERE gv.geofence_id = ?""" + filter + " ORDER BY v.plate_no, v.id",
                (rs, row) -> new VehicleRef(rs.getLong("id"), rs.getString("plate_no"),
                        rs.getLong("fleet_id"), rs.getString("fleet_name")), args.toArray());
    }

    private FenceHeader header(ResultSet rs, int row) throws SQLException {
        return new FenceHeader(rs.getLong("id"), rs.getString("fence_name"), rs.getString("fence_type"),
                nullableDouble(rs, "center_lng"), nullableDouble(rs, "center_lat"), nullableDouble(rs, "radius"),
                vertices(rs.getString("vertices")), rs.getBoolean("enter_enabled"), rs.getBoolean("leave_enabled"),
                rs.getString("status"), rs.getLong("version"),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private List<Vertex> vertices(String raw) {
        if (raw == null) {
            return List.of();
        }
        JsonNode node = json.readTree(raw);
        List<Vertex> vertices = new ArrayList<>(node.size());
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            vertices.add(new Vertex(item.path("lng").asDouble(), item.path("lat").asDouble()));
        }
        return List.copyOf(vertices);
    }

    private GeofenceView view(FenceHeader header, FleetScope scope) {
        return new GeofenceView(header.id(), header.fenceName(), header.fenceType(), header.centerLng(),
                header.centerLat(), header.radiusM(), header.vertices(), header.enterEnabled(),
                header.leaveEnabled(), header.status(), header.version(), vehicles(header.id(), scope),
                header.updatedAt());
    }

    private String polygonWkt(List<Vertex> vertices) {
        StringBuilder wkt = new StringBuilder("POLYGON((");
        for (Vertex vertex : vertices) {
            wkt.append(String.format(Locale.ROOT, "%.7f %.7f, ", vertex.lng(), vertex.lat()));
        }
        Vertex first = vertices.getFirst();
        wkt.append(String.format(Locale.ROOT, "%.7f %.7f))", first.lng(), first.lat()));
        return wkt.toString();
    }

    private Double nullableDouble(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).doubleValue();
    }

    private void setNullableDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private void setNullableBoolean(PreparedStatement ps, int index, Boolean value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BOOLEAN);
        } else {
            ps.setBoolean(index, value);
        }
    }

    private void audit(UserAccount actor, String action, long id, String detail) {
        auditService.record(AuditService.CATEGORY_GEOFENCE, action, "geofence:" + id,
                AuditService.RESULT_SUCCESS, actor.getId(), actor.getUsername(), null, detail);
    }

    public record GeofenceRequest(@NotBlank @Size(max = 64) String fenceName, @NotBlank String fenceType,
                                  Double centerLng, Double centerLat, Double radiusM, List<Vertex> vertices,
                                  Boolean enterEnabled, Boolean leaveEnabled, List<Long> vehicleIds) {
    }

    public record StatusRequest(@NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status) {
    }

    public record VehiclesRequest(@NotNull List<@NotNull @Positive Long> vehicleIds) {
    }

    public record VehicleRef(long id, String plateNo, long fleetId, String fleetName) {
    }

    public record GeofenceView(long id, String fenceName, String fenceType, Double centerLng, Double centerLat,
                               Double radiusM, List<Vertex> vertices, boolean enterEnabled, boolean leaveEnabled,
                               String status, long version, List<VehicleRef> vehicles, LocalDateTime updatedAt) {
    }

    private record VehicleRow(long id, String plateNo, long fleetId, String fleetName) {
    }

    private record FenceHeader(long id, String fenceName, String fenceType, Double centerLng, Double centerLat,
                               Double radiusM, List<Vertex> vertices, boolean enterEnabled, boolean leaveEnabled,
                               String status, long version, LocalDateTime updatedAt) {
    }
}