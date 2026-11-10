package com.vms.driver;

import com.vms.audit.AuditService;
import com.vms.fleet.VehicleService;
import com.vms.identity.access.FleetScope;
import com.vms.identity.access.FleetScopeResolver;
import com.vms.identity.access.FleetScopeSql;
import com.vms.identity.account.UserAccount;
import com.vms.identity.security.ForbiddenException;
import com.vms.identity.security.RoleCode;
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
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 司机档案与车辆关联。关联只表示业务人员维护的当前司机，不认证实际驾驶员身份；
 * 变更依赖生效区间保留历史，报警所需快照由 12/13 复验。
 */
@Service
public class DriverService {

    private static final String SELECT = """
            SELECT d.id, d.driver_no, d.name, d.phone, d.fleet_id, f.fleet_name, d.status,
                   b.vehicle_id, v.plate_no
            FROM driver d JOIN fleet f ON f.id = d.fleet_id
            LEFT JOIN vehicle_driver_binding b ON b.current_driver_id = d.id
            LEFT JOIN vehicle v ON v.id = b.vehicle_id
            """;

    private final JdbcTemplate jdbc;
    private final VehicleService vehicles;
    private final FleetScopeResolver scopes;
    private final AuditService audit;
    private final Clock clock;

    public DriverService(JdbcTemplate jdbc, VehicleService vehicles, FleetScopeResolver scopes,
                         AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.vehicles = vehicles;
        this.scopes = scopes;
        this.audit = audit;
        this.clock = clock;
    }

    public List<DriverView> list(UserAccount actor, Long fleetId) {
        FleetScope scope = scopes.resolve(actor);
        if (fleetId != null && !scope.contains(fleetId)) {
            throw new ForbiddenException();
        }
        List<Object> args = new ArrayList<>();
        String where = " WHERE " + FleetScopeSql.where("d.fleet_id", scope, args);
        if (fleetId != null) {
            where += " AND d.fleet_id = ?";
            args.add(fleetId);
        }
        RoleCode role = role(actor);
        return jdbc.query(SELECT + where + " ORDER BY d.driver_no",
                (rs, row) -> view(rs, role), args.toArray());
    }

    public DriverView get(UserAccount actor, long id) {
        FleetScope scope = scopes.resolve(actor);
        List<Object> args = new ArrayList<>();
        String where = FleetScopeSql.where("d.fleet_id", scope, args);
        args.add(id);
        RoleCode role = role(actor);
        List<DriverView> rows = jdbc.query(SELECT + " WHERE " + where + " AND d.id = ?",
                (rs, row) -> view(rs, role), args.toArray());
        if (rows.isEmpty()) {
            if (!scope.all()) {
                throw new ForbiddenException();
            }
            throw new NotFoundException("DRIVER_NOT_FOUND", "司机不存在");
        }
        return rows.getFirst();
    }

    @Transactional
    public DriverView create(UserAccount actor, String driverNo, String name, String phone, long fleetId) {
        vehicles.requireEnabledFleet(fleetId);
        LocalDateTime now = now();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO driver (driver_no, name, phone, fleet_id, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'ENABLED', ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, driverNo.strip());
                statement.setString(2, name.strip());
                statement.setString(3, phone);
                statement.setLong(4, fleetId);
                statement.setObject(5, now);
                statement.setObject(6, now);
                return statement;
            }, key);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("DRIVER_NO_EXISTS", "司机编号已存在");
        }
        long id = key.getKey().longValue();
        record(actor, "CREATE", "driver:" + id, "driverNo=" + driverNo.strip() + ", fleetId=" + fleetId);
        return get(actor, id);
    }

    @Transactional
    public DriverView update(UserAccount actor, long id, String name, String phone, String status) {
        if (name == null && phone == null && status == null) {
            // 没有字段变化时不写审计，避免空操作污染操作记录
            return get(actor, id);
        }
        lockDriver(id);
        if (name != null) {
            jdbc.update("UPDATE driver SET name = ?, updated_at = ? WHERE id = ?", name.strip(), now(), id);
        }
        if (phone != null) {
            jdbc.update("UPDATE driver SET phone = ?, updated_at = ? WHERE id = ?", phone, now(), id);
        }
        if (status != null) {
            jdbc.update("UPDATE driver SET status = ?, updated_at = ? WHERE id = ?", status, now(), id);
        }
        String detail = (name == null ? "" : "name=" + name.strip())
                + (phone == null ? "" : (name == null ? "" : ", ") + "phone=" + phone)
                + (status == null ? "" : (name == null && phone == null ? "" : ", ") + "status=" + status);
        record(actor, "UPDATE", "driver:" + id, detail);
        return get(actor, id);
    }

    /**
     * 车辆当前司机关联。driverId 为空表示解绑；同一车辆并发维护时以车辆行锁与当前关联复读判别，
     * 第二次写入因生效时间未推进或绑定已变更而失败，不形成两条当前关联。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CurrentAssociation bind(UserAccount actor, long vehicleId, Long driverId) {
        Long observed = currentDriverId(vehicleId, false);
        TreeMap<Long, String> lockedDrivers = new TreeMap<>();
        if (observed != null) {
            lockedDrivers.put(observed, lockDriver(observed));
        }
        if (driverId != null) {
            lockedDrivers.put(driverId, lockDriver(driverId));
        }
        vehicles.lock(vehicleId);
        Long current = currentDriverId(vehicleId, true);
        if (!Objects.equals(observed, current)) {
            throw new ConflictException("BINDING_CHANGED", "司机关联已被其他操作修改，请刷新后重试");
        }
        if (Objects.equals(current, driverId)) {
            return current(actor, vehicleId);
        }
        if (driverId != null) {
            if (!"ENABLED".equals(lockedDrivers.get(driverId))) {
                throw new ConflictException("DRIVER_DISABLED", "司机已停用，不能关联");
            }
            List<String> vehicleStatus = jdbc.queryForList("SELECT status FROM vehicle WHERE id = ?",
                    String.class, vehicleId);
            if (vehicleStatus.isEmpty()) {
                throw new NotFoundException("VEHICLE_NOT_FOUND", "车辆不存在");
            }
            if (!"ENABLED".equals(vehicleStatus.getFirst())) {
                throw new ConflictException("VEHICLE_DISABLED", "车辆已停用，不能关联司机");
            }
            if (!jdbc.queryForList("SELECT vehicle_id FROM vehicle_driver_binding WHERE current_driver_id = ? FOR UPDATE",
                    Long.class, driverId).isEmpty()) {
                throw new ConflictException("DRIVER_OCCUPIED", "司机已关联其他车辆，请先解除");
            }
        }
        LocalDateTime now = now();
        List<Object> args = new ArrayList<>();
        args.add(vehicleId);
        String filter = "vehicle_id = ?";
        if (!lockedDrivers.isEmpty()) {
            filter += " OR driver_id IN ("
                    + String.join(",", java.util.Collections.nCopies(lockedDrivers.size(), "?")) + ")";
            args.addAll(lockedDrivers.keySet());
        }
        LocalDateTime vehicleCreated = jdbc.queryForObject("SELECT created_at FROM vehicle WHERE id = ?",
                LocalDateTime.class, vehicleId);
        EffectiveTime.requireNotBeforeArchive(now, vehicleCreated);
        for (LocalDateTime boundary : jdbc.query("SELECT effective_from, effective_to FROM vehicle_driver_binding WHERE "
                + filter + " FOR UPDATE", (rs, row) -> {
                    LocalDateTime end = rs.getObject("effective_to", LocalDateTime.class);
                    return end == null ? rs.getObject("effective_from", LocalDateTime.class) : end;
                }, args.toArray())) {
            EffectiveTime.requireAfter(now, boundary);
        }
        try {
            jdbc.update("UPDATE vehicle_driver_binding SET effective_to = ? WHERE current_vehicle_id = ?", now, vehicleId);
            if (driverId != null) {
                jdbc.update("""
                        INSERT INTO vehicle_driver_binding (driver_id, vehicle_id, effective_from)
                        VALUES (?, ?, ?)
                        """, driverId, vehicleId, now);
            }
        } catch (DuplicateKeyException e) {
            throw new ConflictException("BINDING_CONFLICT", "车辆或司机已被关联，请刷新后重试");
        }
        record(actor, driverId == null ? "UNASSOCIATE" : "ASSOCIATE", "vehicle:" + vehicleId,
                "fromDriverId=" + current + ", toDriverId=" + driverId + ", effectiveFrom=" + now);
        return current(actor, vehicleId);
    }

    public CurrentAssociation current(UserAccount actor, long vehicleId) {
        vehicles.get(actor, vehicleId);
        RoleCode role = role(actor);
        List<CurrentAssociation> rows = jdbc.query("""
                SELECT b.driver_id, d.driver_no, d.name, d.phone, b.vehicle_id, v.plate_no, b.effective_from
                FROM vehicle_driver_binding b JOIN driver d ON d.id = b.driver_id
                JOIN vehicle v ON v.id = b.vehicle_id
                WHERE b.current_vehicle_id = ?
                """, (rs, row) -> new CurrentAssociation(rs.getLong("driver_id"), rs.getString("driver_no"),
                rs.getString("name"), phoneFor(role, rs.getString("phone")),
                rs.getLong("vehicle_id"), rs.getString("plate_no"),
                rs.getObject("effective_from", LocalDateTime.class)), vehicleId);
        return rows.isEmpty()
                ? new CurrentAssociation(null, null, null, null, vehicleId, null, null)
                : rows.getFirst();
    }

    /**
     * 完整关联历史可能包含车辆转队前属于其他车队的司机，仅全范围角色可读；车队长只经车辆视图看当前关联。
     */
    public List<AssociationView> history(UserAccount actor, long vehicleId) {
        vehicles.get(actor, vehicleId);
        if (!scopes.resolve(actor).all()) {
            throw new ForbiddenException();
        }
        return jdbc.query("""
                SELECT b.id, b.driver_id, d.driver_no, b.vehicle_id, v.plate_no, b.effective_from, b.effective_to
                FROM vehicle_driver_binding b JOIN driver d ON d.id = b.driver_id
                JOIN vehicle v ON v.id = b.vehicle_id
                WHERE b.vehicle_id = ? ORDER BY b.effective_from, b.id
                """, (rs, row) -> new AssociationView(rs.getLong("id"), rs.getLong("driver_id"),
                rs.getString("driver_no"), rs.getLong("vehicle_id"), rs.getString("plate_no"),
                rs.getObject("effective_from", LocalDateTime.class),
                rs.getObject("effective_to", LocalDateTime.class)), vehicleId);
    }

    /**
     * 车队长只看到掩码号码；管理员与监控员按职责看完整号码。掩码在响应层完成，不进入日志或审计明细以外的位置。
     */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String phoneFor(RoleCode role, String phone) {
        return role == RoleCode.FLEET_LEADER ? maskPhone(phone) : phone;
    }

    private RoleCode role(UserAccount actor) {
        return RoleCode.parse(actor.getRoleCode()).orElseThrow(
                () -> new IllegalStateException("未知角色：" + actor.getRoleCode()));
    }

    private Long currentDriverId(long vehicleId, boolean lock) {
        List<Long> ids = jdbc.queryForList("SELECT driver_id FROM vehicle_driver_binding WHERE current_vehicle_id = ?"
                + (lock ? " FOR UPDATE" : ""), Long.class, vehicleId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private String lockDriver(long id) {
        List<String> statuses = jdbc.queryForList("SELECT status FROM driver WHERE id = ? FOR UPDATE",
                String.class, id);
        if (statuses.isEmpty()) {
            throw new NotFoundException("DRIVER_NOT_FOUND", "司机不存在");
        }
        return statuses.getFirst();
    }

    private LocalDateTime now() {
        return EffectiveTime.now(clock);
    }

    private void record(UserAccount actor, String action, String target, String detail) {
        audit.record(AuditService.CATEGORY_DRIVER, action, target,
                AuditService.RESULT_SUCCESS, actor.getId(), actor.getUsername(), null, detail);
    }

    private static DriverView view(ResultSet rs, RoleCode role) throws SQLException {
        String phone = rs.getString("phone");
        return new DriverView(rs.getLong("id"), rs.getString("driver_no"), rs.getString("name"),
                role == RoleCode.FLEET_LEADER ? maskPhone(phone) : phone,
                rs.getLong("fleet_id"), rs.getString("fleet_name"), rs.getString("status"),
                rs.getObject("vehicle_id", Long.class), rs.getString("plate_no"));
    }

    public record DriverView(long id, String driverNo, String name, String phone, long fleetId,
                             String fleetName, String status, Long vehicleId, String plateNo) { }

    public record CurrentAssociation(Long driverId, String driverNo, String driverName, String phone,
                                     long vehicleId, String plateNo, LocalDateTime effectiveFrom) { }

    public record AssociationView(long id, long driverId, String driverNo, long vehicleId,
                                  String plateNo, LocalDateTime effectiveFrom, LocalDateTime effectiveTo) { }
}
