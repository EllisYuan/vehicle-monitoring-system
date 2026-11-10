package com.vms.fleet;

import com.vms.audit.AuditService;
import com.vms.identity.access.FleetScope;
import com.vms.identity.access.FleetScopeResolver;
import com.vms.identity.access.FleetScopeSql;
import com.vms.identity.account.UserAccount;
import com.vms.identity.security.ForbiddenException;
import com.vms.infrastructure.EffectiveTime;
import com.vms.infrastructure.web.ConflictException;
import com.vms.infrastructure.web.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VehicleService {

    private static final String SELECT = """
            SELECT v.id, v.plate_no, v.fleet_id, f.fleet_name, v.status,
                   t.id AS terminal_id, t.terminal_no
            FROM vehicle v JOIN fleet f ON f.id = v.fleet_id
            LEFT JOIN terminal_vehicle_binding b ON b.current_vehicle_id = v.id
            LEFT JOIN terminal t ON t.id = b.terminal_id
            """;

    private final JdbcTemplate jdbc;
    private final FleetScopeResolver scopes;
    private final AuditService audit;
    private final Clock clock;

    public VehicleService(JdbcTemplate jdbc, FleetScopeResolver scopes, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.audit = audit;
        this.clock = clock;
    }

    public List<VehicleView> list(UserAccount user, Long fleetId) {
        FleetScope scope = scopes.resolve(user);
        if (fleetId != null && !scope.contains(fleetId)) {
            throw new ForbiddenException();
        }
        List<Object> args = new ArrayList<>();
        String where = " WHERE " + FleetScopeSql.where("v.fleet_id", scope, args);
        if (fleetId != null) {
            where += " AND v.fleet_id = ?";
            args.add(fleetId);
        }
        return jdbc.query(SELECT + where + " ORDER BY v.plate_no", VehicleService::view, args.toArray());
    }

    public VehicleView get(UserAccount user, long id) {
        FleetScope scope = scopes.resolve(user);
        List<Object> args = new ArrayList<>();
        String where = " WHERE " + FleetScopeSql.where("v.fleet_id", scope, args);
        args.add(id);
        List<VehicleView> rows = jdbc.query(SELECT + where + " AND v.id = ?", VehicleService::view, args.toArray());
        if (rows.isEmpty()) {
            if (!scope.all()) {
                throw new ForbiddenException();
            }
            throw new NotFoundException("VEHICLE_NOT_FOUND", "车辆不存在");
        }
        return rows.getFirst();
    }

    @Transactional
    public VehicleView create(UserAccount actor, String plateNo, long fleetId) {
        requireEnabledFleet(fleetId);
        LocalDateTime now = now();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO vehicle (plate_no, fleet_id, status, created_at, updated_at)
                        VALUES (?, ?, 'ENABLED', ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, plateNo.strip());
                statement.setLong(2, fleetId);
                statement.setObject(3, now);
                statement.setObject(4, now);
                return statement;
            }, key);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("PLATE_NO_EXISTS", "车牌已存在");
        }
        long id = key.getKey().longValue();
        jdbc.update("""
                INSERT INTO vehicle_fleet_history (vehicle_id, fleet_id, effective_from)
                VALUES (?, ?, ?)
                """, id, fleetId, now);
        record(actor, "CREATE", id, "plateNo=" + plateNo.strip() + ", fleetId=" + fleetId);
        return get(actor, id);
    }

    @Transactional
    public VehicleView update(UserAccount actor, long id, String plateNo, String status) {
        if (plateNo == null && status == null) {
            // 没有字段变化时不写审计，避免空操作污染操作记录
            return get(actor, id);
        }
        lock(id);
        try {
            if (plateNo != null) {
                jdbc.update("UPDATE vehicle SET plate_no = ?, updated_at = ? WHERE id = ?", plateNo.strip(), now(), id);
            }
            if (status != null) {
                jdbc.update("UPDATE vehicle SET status = ?, updated_at = ? WHERE id = ?", status, now(), id);
            }
        } catch (DuplicateKeyException e) {
            throw new ConflictException("PLATE_NO_EXISTS", "车牌已存在");
        }
        String detail = (plateNo == null ? "" : "plateNo=" + plateNo.strip())
                + (status == null ? "" : (plateNo == null ? "" : ", ") + "status=" + status);
        record(actor, "UPDATE", id, detail);
        return get(actor, id);
    }

    @Transactional
    public VehicleView transfer(UserAccount actor, long id, long fleetId) {
        lock(id);
        VehicleView vehicle = get(actor, id);
        if (vehicle.fleetId() == fleetId) {
            // 重复转入当前车队为幂等操作；不因车队当前已停用而报错
            return vehicle;
        }
        requireEnabledFleet(fleetId);
        List<LocalDateTime> currentFrom = jdbc.queryForList("""
                SELECT effective_from FROM vehicle_fleet_history WHERE current_vehicle_id = ? FOR UPDATE
                """, LocalDateTime.class, id);
        if (currentFrom.isEmpty()) {
            // 建车时必写初始区间；缺失说明数据被绕过应用改动，拒绝而不是按当前档案猜测
            throw new ConflictException("VEHICLE_HISTORY_MISSING", "车辆缺少当前车队归属记录，请刷新后重试");
        }
        LocalDateTime now = now();
        EffectiveTime.requireAfter(now, currentFrom.getFirst());
        jdbc.update("UPDATE vehicle_fleet_history SET effective_to = ? WHERE current_vehicle_id = ?", now, id);
        jdbc.update("INSERT INTO vehicle_fleet_history (vehicle_id, fleet_id, effective_from) VALUES (?, ?, ?)",
                id, fleetId, now);
        jdbc.update("UPDATE vehicle SET fleet_id = ?, updated_at = ? WHERE id = ?", fleetId, now, id);
        record(actor, "TRANSFER", id, "fromFleetId=" + vehicle.fleetId() + ", toFleetId=" + fleetId
                + ", effectiveFrom=" + now);
        return get(actor, id);
    }

    public List<FleetHistory> history(UserAccount actor, long id) {
        get(actor, id);
        FleetScope scope = scopes.resolve(actor);
        List<Object> args = new ArrayList<>();
        args.add(id);
        String filter = "";
        if (!scope.all()) {
            if (scope.fleetIds().isEmpty()) {
                return List.of();
            }
            filter = " AND " + FleetScopeSql.where("h.fleet_id", scope, args);
        }
        return jdbc.query("""
                SELECT h.id, h.vehicle_id, h.fleet_id, f.fleet_name, h.effective_from, h.effective_to
                FROM vehicle_fleet_history h JOIN fleet f ON f.id = h.fleet_id
                WHERE h.vehicle_id = ?
                """ + filter + " ORDER BY h.effective_from, h.id", (rs, row) -> new FleetHistory(
                rs.getLong("id"), rs.getLong("vehicle_id"), rs.getLong("fleet_id"), rs.getString("fleet_name"),
                rs.getObject("effective_from", LocalDateTime.class), rs.getObject("effective_to", LocalDateTime.class)),
                args.toArray());
    }

    public void lock(long id) {
        if (jdbc.queryForList("SELECT id FROM vehicle WHERE id = ? FOR UPDATE", Long.class, id).isEmpty()) {
            throw new NotFoundException("VEHICLE_NOT_FOUND", "车辆不存在");
        }
    }

    /**
     * 档案创建时间用于判定生效区间不得早于建档，与车辆行锁配合，不对外暴露为视图字段。
     */
    public LocalDateTime createdAt(long id) {
        return jdbc.queryForObject("SELECT created_at FROM vehicle WHERE id = ?", LocalDateTime.class, id);
    }

    public void requireEnabledFleet(long id) {
        List<String> statuses = jdbc.queryForList("SELECT status FROM fleet WHERE id = ? FOR UPDATE", String.class, id);
        if (statuses.isEmpty()) {
            throw new NotFoundException("FLEET_NOT_FOUND", "车队不存在");
        }
        if (!"ENABLED".equals(statuses.getFirst())) {
            throw new ConflictException("FLEET_DISABLED", "车队已停用");
        }
    }

    private LocalDateTime now() {
        return EffectiveTime.now(clock);
    }

    private void record(UserAccount actor, String action, long id, String detail) {
        audit.record(AuditService.CATEGORY_VEHICLE, action, "vehicle:" + id, AuditService.RESULT_SUCCESS,
                actor.getId(), actor.getUsername(), null, detail);
    }

    private static VehicleView view(ResultSet rs, int row) throws SQLException {
        return new VehicleView(rs.getLong("id"), rs.getString("plate_no"), rs.getLong("fleet_id"),
                rs.getString("fleet_name"), rs.getString("status"), rs.getObject("terminal_id", Long.class),
                rs.getString("terminal_no"));
    }

    public record VehicleView(long id, String plateNo, long fleetId, String fleetName,
                              String status, Long terminalId, String terminalNo) { }

    public record FleetHistory(long id, long vehicleId, long fleetId, String fleetName,
                               LocalDateTime effectiveFrom, LocalDateTime effectiveTo) { }
}
