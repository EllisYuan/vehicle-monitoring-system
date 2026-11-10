package com.vms.terminal;

import com.vms.audit.AuditService;
import com.vms.fleet.VehicleService;
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
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TerminalService {

    private static final String SELECT = """
            SELECT t.id, t.terminal_no, t.status, v.id AS vehicle_id, v.plate_no, v.fleet_id
            FROM terminal t LEFT JOIN terminal_vehicle_binding b ON b.current_terminal_id = t.id
            LEFT JOIN vehicle v ON v.id = b.vehicle_id
            """;

    private final JdbcTemplate jdbc;
    private final VehicleService vehicles;
    private final FleetScopeResolver scopes;
    private final AuditService audit;
    private final Clock clock;

    public TerminalService(JdbcTemplate jdbc, VehicleService vehicles, FleetScopeResolver scopes,
                           AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.vehicles = vehicles;
        this.scopes = scopes;
        this.audit = audit;
        this.clock = clock;
    }

    public List<TerminalView> list(UserAccount actor) {
        FleetScope scope = scopes.resolve(actor);
        List<Object> args = new ArrayList<>();
        String where = FleetScopeSql.where("v.fleet_id", scope, args);
        return jdbc.query(SELECT + " WHERE " + where + " ORDER BY t.terminal_no",
                TerminalService::view, args.toArray());
    }

    public TerminalView get(UserAccount actor, long id) {
        FleetScope scope = scopes.resolve(actor);
        List<Object> args = new ArrayList<>();
        String where = FleetScopeSql.where("v.fleet_id", scope, args);
        args.add(id);
        List<TerminalView> rows = jdbc.query(SELECT + " WHERE " + where + " AND t.id = ?",
                TerminalService::view, args.toArray());
        if (rows.isEmpty()) {
            if (!scope.all()) {
                throw new ForbiddenException();
            }
            throw new NotFoundException("TERMINAL_NOT_FOUND", "终端不存在");
        }
        return rows.getFirst();
    }

    @Transactional
    public TerminalView create(UserAccount actor, String terminalNo) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        LocalDateTime now = now();
        try {
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO terminal (terminal_no, status, created_at, updated_at)
                        VALUES (?, 'ENABLED', ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, terminalNo);
                statement.setObject(2, now);
                statement.setObject(3, now);
                return statement;
            }, key);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("TERMINAL_NO_EXISTS", "终端编号已登记");
        }
        long id = key.getKey().longValue();
        record(actor, "REGISTER", "terminal:" + id, "terminalNo=" + terminalNo);
        return get(actor, id);
    }

    @Transactional
    public TerminalView update(UserAccount actor, long id, String status) {
        lockTerminal(id);
        jdbc.update("UPDATE terminal SET status = ?, updated_at = ? WHERE id = ?", status, now(), id);
        record(actor, "UPDATE_STATUS", "terminal:" + id, "status=" + status);
        return get(actor, id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public VehicleService.VehicleView bind(UserAccount actor, long vehicleId, Long terminalId) {
        Long observed = currentTerminal(vehicleId, false);
        TreeMap<Long, TerminalSnapshot> lockedTerminals = new TreeMap<>();
        if (observed != null) {
            lockedTerminals.put(observed, lockTerminal(observed));
        }
        if (terminalId != null) {
            lockedTerminals.put(terminalId, lockTerminal(terminalId));
        }
        vehicles.lock(vehicleId);
        Long current = currentTerminal(vehicleId, true);
        if (!Objects.equals(observed, current)) {
            throw new ConflictException("BINDING_CHANGED", "绑定已被其他操作修改，请刷新后重试");
        }
        VehicleService.VehicleView vehicle = vehicles.get(actor, vehicleId);
        if (Objects.equals(current, terminalId)) {
            return vehicle;
        }
        if (terminalId != null) {
            if (!"ENABLED".equals(vehicle.status())) {
                throw new ConflictException("VEHICLE_DISABLED", "车辆已停用，不能绑定终端");
            }
            if (!"ENABLED".equals(lockedTerminals.get(terminalId).status())) {
                throw new ConflictException("TERMINAL_DISABLED", "终端已停用，不能绑定");
            }
            if (!jdbc.queryForList("SELECT vehicle_id FROM terminal_vehicle_binding WHERE current_terminal_id = ? FOR UPDATE",
                    Long.class, terminalId).isEmpty()) {
                throw new ConflictException("TERMINAL_OCCUPIED", "终端已绑定其他车辆，请先解绑");
            }
        }
        LocalDateTime now = now();
        List<Object> args = new ArrayList<>();
        args.add(vehicleId);
        String filter = "vehicle_id = ?";
        if (!lockedTerminals.isEmpty()) {
            filter += " OR terminal_id IN ("
                    + String.join(",", java.util.Collections.nCopies(lockedTerminals.size(), "?")) + ")";
            args.addAll(lockedTerminals.keySet());
        }
        LocalDateTime vehicleCreated = jdbc.queryForObject("SELECT created_at FROM vehicle WHERE id = ?",
                LocalDateTime.class, vehicleId);
        EffectiveTime.requireNotBeforeArchive(now, vehicleCreated);
        for (TerminalSnapshot terminal : lockedTerminals.values()) {
            EffectiveTime.requireNotBeforeArchive(now, terminal.createdAt());
        }
        List<LocalDateTime> boundaries = jdbc.query("SELECT effective_from, effective_to FROM terminal_vehicle_binding WHERE "
                + filter + " FOR UPDATE", (rs, row) -> {
                    LocalDateTime end = rs.getObject("effective_to", LocalDateTime.class);
                    return end == null ? rs.getObject("effective_from", LocalDateTime.class) : end;
                }, args.toArray());
        for (LocalDateTime boundary : boundaries) {
            EffectiveTime.requireAfter(now, boundary);
        }
        try {
            jdbc.update("UPDATE terminal_vehicle_binding SET effective_to = ? WHERE current_vehicle_id = ?", now, vehicleId);
            if (terminalId != null) {
                jdbc.update("INSERT INTO terminal_vehicle_binding (terminal_id, vehicle_id, effective_from) VALUES (?, ?, ?)",
                        terminalId, vehicleId, now);
            }
        } catch (DuplicateKeyException e) {
            throw new ConflictException("BINDING_CONFLICT", "车辆或终端已被绑定，请刷新后重试");
        }
        record(actor, terminalId == null ? "UNBIND" : "BIND", "vehicle:" + vehicleId,
                "fromTerminalId=" + current + ", toTerminalId=" + terminalId + ", effectiveFrom=" + now);
        return vehicles.get(actor, vehicleId);
    }

    /**
     * 完整绑定历史包含车辆转队前的其他车队终端，属未授权历史，仅全范围角色可读。
     * 车队长只能通过车辆视图看到当前绑定关系。
     */
    public List<BindingView> history(UserAccount actor, long vehicleId) {
        vehicles.get(actor, vehicleId);
        if (!scopes.resolve(actor).all()) {
            throw new ForbiddenException();
        }
        return jdbc.query("""
                SELECT b.id, b.terminal_id, t.terminal_no, b.vehicle_id, b.effective_from, b.effective_to
                FROM terminal_vehicle_binding b JOIN terminal t ON t.id = b.terminal_id
                WHERE b.vehicle_id = ? ORDER BY b.effective_from, b.id
                """, (rs, row) -> new BindingView(rs.getLong("id"), rs.getLong("terminal_id"),
                rs.getString("terminal_no"), rs.getLong("vehicle_id"),
                rs.getObject("effective_from", LocalDateTime.class), rs.getObject("effective_to", LocalDateTime.class)), vehicleId);
    }

    private Long currentTerminal(long vehicleId, boolean lock) {
        List<Long> ids = jdbc.queryForList("SELECT terminal_id FROM terminal_vehicle_binding WHERE current_vehicle_id = ?"
                + (lock ? " FOR UPDATE" : ""), Long.class, vehicleId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    /**
     * 锁定终端行并带回状态与创建时间，绑定流程据此判断可绑定性与生效区间，避免重复查询同一行。
     */
    private TerminalSnapshot lockTerminal(long id) {
        List<TerminalSnapshot> rows = jdbc.query(
                "SELECT status, created_at FROM terminal WHERE id = ? FOR UPDATE", (rs, row) ->
                        new TerminalSnapshot(rs.getString("status"),
                                rs.getObject("created_at", LocalDateTime.class)), id);
        if (rows.isEmpty()) {
            throw new NotFoundException("TERMINAL_NOT_FOUND", "终端不存在");
        }
        return rows.getFirst();
    }

    private LocalDateTime now() {
        return EffectiveTime.now(clock);
    }

    private void record(UserAccount actor, String action, String target, String detail) {
        audit.record(AuditService.CATEGORY_TERMINAL, action, target, AuditService.RESULT_SUCCESS,
                actor.getId(), actor.getUsername(), null, detail);
    }

    private static TerminalView view(ResultSet rs, int row) throws SQLException {
        return new TerminalView(rs.getLong("id"), rs.getString("terminal_no"), rs.getString("status"),
                rs.getObject("vehicle_id", Long.class), rs.getString("plate_no"),
                rs.getObject("fleet_id", Long.class));
    }

    public record TerminalView(long id, String terminalNo, String status, Long vehicleId, String plateNo, Long fleetId) { }
    private record TerminalSnapshot(String status, LocalDateTime createdAt) { }
    public record BindingView(long id, long terminalId, String terminalNo, long vehicleId,
                              LocalDateTime effectiveFrom, LocalDateTime effectiveTo) { }
}
