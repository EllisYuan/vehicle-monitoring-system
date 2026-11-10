package com.vms.fleet;

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
        // 车辆超出授权范围时该左连接不成立，当前车辆与车牌一并隐藏，不泄露其他车队车牌
        String select = select(FleetScopeSql.where("v.fleet_id", scope, args));
        String where = " WHERE " + FleetScopeSql.where("d.fleet_id", scope, args);
        if (fleetId != null) {
            where += " AND d.fleet_id = ?";
            args.add(fleetId);
        }
        RoleCode role = role(actor);
        return jdbc.query(select + where + " ORDER BY d.driver_no",
                (rs, row) -> view(rs, role), args.toArray());
    }

    public DriverView get(UserAccount actor, long id) {
        FleetScope scope = scopes.resolve(actor);
        List<Object> args = new ArrayList<>();
        String select = select(FleetScopeSql.where("v.fleet_id", scope, args));
        String where = FleetScopeSql.where("d.fleet_id", scope, args);
        args.add(id);
        RoleCode role = role(actor);
        List<DriverView> rows = jdbc.query(select + " WHERE " + where + " AND d.id = ?",
                (rs, row) -> view(rs, role), args.toArray());
        if (rows.isEmpty()) {
            if (!scope.all()) {
                throw new ForbiddenException();
            }
            throw new NotFoundException("DRIVER_NOT_FOUND", "司机不存在");
        }
        return rows.getFirst();
    }

    private static String select(String vehicleScope) {
        return """
                SELECT d.id, d.driver_no, d.name, d.phone, d.fleet_id, f.fleet_name, d.status,
                       v.id AS vehicle_id, v.plate_no
                FROM driver d JOIN fleet f ON f.id = d.fleet_id
                LEFT JOIN vehicle_driver_binding b ON b.current_driver_id = d.id
                LEFT JOIN vehicle v ON v.id = b.vehicle_id AND """ + " " + vehicleScope + "\n";
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
    public DriverView update(UserAccount actor, long id, String driverNo, String name, String phone, String status) {
        if (driverNo == null && name == null && phone == null && status == null) {
            // 没有字段变化时不写审计，避免空操作污染操作记录
            return get(actor, id);
        }
        lockDriver(id);
        try {
            if (driverNo != null) {
                jdbc.update("UPDATE driver SET driver_no = ?, updated_at = ? WHERE id = ?",
                        driverNo.strip(), now(), id);
            }
            if (name != null) {
                jdbc.update("UPDATE driver SET name = ?, updated_at = ? WHERE id = ?", name.strip(), now(), id);
            }
            if (phone != null) {
                jdbc.update("UPDATE driver SET phone = ?, updated_at = ? WHERE id = ?", phone, now(), id);
            }
            if (status != null) {
                jdbc.update("UPDATE driver SET status = ?, updated_at = ? WHERE id = ?", status, now(), id);
            }
        } catch (DuplicateKeyException e) {
            throw new ConflictException("DRIVER_NO_EXISTS", "司机编号已存在");
        }
        String detail = (driverNo == null ? "" : "driverNo=" + driverNo.strip())
                + (name == null ? "" : (driverNo == null ? "" : ", ") + "name=" + name.strip())
                + (phone == null ? "" : (driverNo == null && name == null ? "" : ", ") + "phone=" + phone)
                + (status == null ? "" : (driverNo == null && name == null && phone == null ? "" : ", ")
                        + "status=" + status);
        record(actor, "UPDATE", "driver:" + id, detail);
        return get(actor, id);
    }

    /**
     * 车辆当前司机关联。driverId 为空表示解绑；同一司机改挂其他车辆时在本事务内结束原区间，
     * 使「换车」成为一次原子操作，不会留下已解绑的中间态。并发维护以车辆行锁、司机行锁与
     * 当前关联复读判别，不形成两条当前关联。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CurrentAssociation bind(UserAccount actor, long vehicleId, Long driverId) {
        Long observed = currentDriverId(vehicleId, false);
        TreeMap<Long, DriverSnapshot> lockedDrivers = new TreeMap<>();
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
        VehicleService.VehicleView vehicle = vehicles.get(actor, vehicleId);
        Long driverOtherVehicle = null;
        if (driverId != null) {
            DriverSnapshot driver = lockedDrivers.get(driverId);
            if (!"ENABLED".equals(driver.status())) {
                throw new ConflictException("DRIVER_DISABLED", "司机已停用，不能关联");
            }
            if (!"ENABLED".equals(vehicle.status())) {
                throw new ConflictException("VEHICLE_DISABLED", "车辆已停用，不能关联司机");
            }
            // 司机关联只在本车队车辆间建立，避免车队长经车辆详情看到其他车队司机的姓名与电话
            if (driver.fleetId() != vehicle.fleetId()) {
                throw new ConflictException("DRIVER_FLEET_MISMATCH", "司机与车辆不属于同一车队，不能关联");
            }
            // 司机行锁已持有，此处读到的是该司机唯一的当前关联；换车时在同一事务内结束旧区间
            List<Long> otherVehicles = jdbc.queryForList(
                    "SELECT vehicle_id FROM vehicle_driver_binding WHERE current_driver_id = ? FOR UPDATE",
                    Long.class, driverId);
            if (!otherVehicles.isEmpty()) {
                driverOtherVehicle = otherVehicles.getFirst();
            }
        }
        LocalDateTime now = now();
        List<Object> args = new ArrayList<>();
        args.add(vehicleId);
        StringBuilder filter = new StringBuilder("vehicle_id = ?");
        if (driverOtherVehicle != null) {
            filter.append(" OR vehicle_id = ?");
            args.add(driverOtherVehicle);
        }
        if (!lockedDrivers.isEmpty()) {
            filter.append(" OR driver_id IN (")
                    .append(String.join(",", java.util.Collections.nCopies(lockedDrivers.size(), "?")))
                    .append(")");
            args.addAll(lockedDrivers.keySet());
        }
        EffectiveTime.requireNotBeforeArchive(now, vehicles.createdAt(vehicleId));
        for (LocalDateTime boundary : jdbc.query("SELECT effective_from, effective_to FROM vehicle_driver_binding WHERE "
                + filter + " FOR UPDATE", (rs, row) -> {
                    LocalDateTime end = rs.getObject("effective_to", LocalDateTime.class);
                    return end == null ? rs.getObject("effective_from", LocalDateTime.class) : end;
                }, args.toArray())) {
            EffectiveTime.requireAfter(now, boundary);
        }
        try {
            jdbc.update("UPDATE vehicle_driver_binding SET effective_to = ? WHERE current_vehicle_id = ?", now, vehicleId);
            if (driverOtherVehicle != null) {
                jdbc.update("UPDATE vehicle_driver_binding SET effective_to = ? WHERE current_vehicle_id = ?",
                        now, driverOtherVehicle);
                record(actor, "UNASSOCIATE", "vehicle:" + driverOtherVehicle,
                        "fromDriverId=" + driverId + ", toDriverId=null, effectiveFrom=" + now
                                + ", movedToVehicleId=" + vehicleId);
            }
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
        List<Object> args = new ArrayList<>();
        // 司机不在授权范围时该行消失，按未关联呈现，不猜测身份或归属
        String driverScope = FleetScopeSql.where("d.fleet_id", scopes.resolve(actor), args);
        args.add(vehicleId);
        List<CurrentAssociation> rows = jdbc.query("""
                SELECT b.driver_id, d.driver_no, d.name, d.phone, b.vehicle_id, v.plate_no, b.effective_from
                FROM vehicle_driver_binding b JOIN driver d ON d.id = b.driver_id AND """ + " "
                + driverScope + """
                 JOIN vehicle v ON v.id = b.vehicle_id
                WHERE b.current_vehicle_id = ?
                """, (rs, row) -> new CurrentAssociation(rs.getLong("driver_id"), rs.getString("driver_no"),
                rs.getString("name"), phoneFor(role, rs.getString("phone")),
                rs.getLong("vehicle_id"), rs.getString("plate_no"),
                rs.getObject("effective_from", LocalDateTime.class)), args.toArray());
        return rows.isEmpty()
                ? new CurrentAssociation(null, null, null, null, vehicleId, null, null)
                : rows.getFirst();
    }

    /**
     * 司机视角的关联历史：以司机为主体列出其关联过的车辆区间。车辆不在授权范围时该行不出现，
     * 避免车队长经司机档案看到其他车队的车牌。
     */
    public List<AssociationView> driverHistory(UserAccount actor, long driverId) {
        get(actor, driverId);
        List<Object> args = new ArrayList<>();
        String vehicleScope = FleetScopeSql.where("v.fleet_id", scopes.resolve(actor), args);
        args.add(driverId);
        return jdbc.query("""
                SELECT b.id, b.driver_id, d.driver_no, b.vehicle_id, v.plate_no, b.effective_from, b.effective_to
                FROM vehicle_driver_binding b JOIN driver d ON d.id = b.driver_id
                JOIN vehicle v ON v.id = b.vehicle_id AND """ + " "
                + vehicleScope + """
                 WHERE b.driver_id = ? ORDER BY b.effective_from, b.id
                """, (rs, row) -> new AssociationView(rs.getLong("id"), rs.getLong("driver_id"),
                rs.getString("driver_no"), rs.getLong("vehicle_id"), rs.getString("plate_no"),
                rs.getObject("effective_from", LocalDateTime.class),
                rs.getObject("effective_to", LocalDateTime.class)), args.toArray());
    }

    /**
     * 车辆视角的关联历史：该车辆历任司机。司机不在授权范围时该行不出现，避免车队长经车辆档案
     * 看到其他车队的司机。
     */
    public List<AssociationView> history(UserAccount actor, long vehicleId) {
        vehicles.get(actor, vehicleId);
        List<Object> args = new ArrayList<>();
        String driverScope = FleetScopeSql.where("d.fleet_id", scopes.resolve(actor), args);
        args.add(vehicleId);
        return jdbc.query("""
                SELECT b.id, b.driver_id, d.driver_no, b.vehicle_id, v.plate_no, b.effective_from, b.effective_to
                FROM vehicle_driver_binding b JOIN driver d ON d.id = b.driver_id AND """ + " "
                + driverScope + """
                 JOIN vehicle v ON v.id = b.vehicle_id
                WHERE b.vehicle_id = ? ORDER BY b.effective_from, b.id
                """, (rs, row) -> new AssociationView(rs.getLong("id"), rs.getLong("driver_id"),
                rs.getString("driver_no"), rs.getLong("vehicle_id"), rs.getString("plate_no"),
                rs.getObject("effective_from", LocalDateTime.class),
                rs.getObject("effective_to", LocalDateTime.class)), args.toArray());
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

    private DriverSnapshot lockDriver(long id) {
        List<DriverSnapshot> rows = jdbc.query("SELECT status, fleet_id FROM driver WHERE id = ? FOR UPDATE",
                (rs, row) -> new DriverSnapshot(rs.getString("status"), rs.getLong("fleet_id")), id);
        if (rows.isEmpty()) {
            throw new NotFoundException("DRIVER_NOT_FOUND", "司机不存在");
        }
        return rows.getFirst();
    }

    private record DriverSnapshot(String status, long fleetId) { }

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
