package com.vms.telemetry;

import com.vms.access.TerminalAccessPort;
import com.vms.identity.access.FleetScope;
import com.vms.identity.access.FleetScopeResolver;
import com.vms.identity.access.FleetScopeSql;
import com.vms.identity.account.UserAccount;
import com.vms.identity.security.ForbiddenException;
import com.vms.infrastructure.ClockConfig;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MonitorSnapshotService {
    private static final String SELECT = """
            SELECT v.id AS vehicle_id,v.plate_no,v.fleet_id,f.fleet_name,v.status,
                   t.id AS terminal_id,t.terminal_no,t.status AS terminal_status,t.last_seen_at,t.ever_authenticated,
                   p.terminal_id AS position_terminal_id,p.gps_time,p.received_at,p.lng,p.lat,p.speed,
                   p.direction,p.status_flag,p.version
            FROM vehicle v JOIN fleet f ON f.id=v.fleet_id
            LEFT JOIN terminal_vehicle_binding b ON b.current_vehicle_id=v.id
            LEFT JOIN terminal t ON t.id=b.terminal_id
            LEFT JOIN vehicle_last_position p ON p.vehicle_id=v.id
            """;
    private final JdbcTemplate jdbc;
    private final FleetScopeResolver scopes;
    private final TerminalAccessPort access;
    private final Clock clock;

    public MonitorSnapshotService(JdbcTemplate jdbc, FleetScopeResolver scopes, TerminalAccessPort access, Clock clock) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.access = access;
        this.clock = clock;
    }

    public Snapshot snapshot(UserAccount user, Long fleetId) {
        FleetScope scope = scopes.resolve(user);
        if (fleetId != null && !scope.contains(fleetId)) throw new ForbiddenException();
        List<Object> args = new ArrayList<>();
        String where = " WHERE " + FleetScopeSql.where("v.fleet_id", scope, args);
        if (fleetId != null) { where += " AND v.fleet_id=?"; args.add(fleetId); }
        return new Snapshot(OffsetDateTime.now(clock), jdbc.query(SELECT + where + " ORDER BY v.plate_no",
                this::view, args.toArray()));
    }

    public MonitorVehicle vehicle(UserAccount user, long vehicleId) {
        List<Object> args = new ArrayList<>();
        String where = " WHERE " + FleetScopeSql.where("v.fleet_id", scopes.resolve(user), args);
        args.add(vehicleId);
        var rows = jdbc.query(SELECT + where + " AND v.id=?", this::view, args.toArray());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private MonitorVehicle view(ResultSet rs, int row) throws SQLException {
        Long terminalId = rs.getObject("terminal_id", Long.class);
        String terminalNo = rs.getString("terminal_no");
        LocalDateTime lastSeen = rs.getObject("last_seen_at", LocalDateTime.class);
        LocalDateTime now = LocalDateTime.now(clock);
        String communication;
        if (!"ENABLED".equals(rs.getString("status")) || terminalId != null
                && !"ENABLED".equals(rs.getString("terminal_status"))) communication = "DISABLED";
        else if (terminalId == null) communication = "UNBOUND";
        else if (!rs.getBoolean("ever_authenticated")) communication = "NOT_CONNECTED";
        else communication = lastSeen != null && !lastSeen.isBefore(now.minusSeconds(180)) && !lastSeen.isAfter(now)
                    && access.current(terminalNo).filter(TerminalAccessPort.SessionView::writable).isPresent()
                    ? "ONLINE" : "OFFLINE";
        Long version = rs.getObject("version", Long.class);
        LatestPosition position = version == null ? null : new LatestPosition(version,
                rs.getLong("position_terminal_id"), offset(rs.getObject("gps_time", LocalDateTime.class)),
                offset(rs.getObject("received_at", LocalDateTime.class)), rs.getDouble("lng"), rs.getDouble("lat"),
                rs.getDouble("speed"), rs.getInt("direction"), (rs.getLong("status_flag") & 1) != 0, true);
        return new MonitorVehicle(rs.getLong("vehicle_id"), rs.getString("plate_no"), rs.getLong("fleet_id"),
                rs.getString("fleet_name"), rs.getString("status"), terminalId, terminalNo, offset(lastSeen), communication, position);
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(ClockConfig.BUSINESS_ZONE).toOffsetDateTime();
    }

    public record Snapshot(OffsetDateTime serverTime, List<MonitorVehicle> vehicles) { }
    public record MonitorVehicle(long vehicleId, String plateNo, long fleetId, String fleetName, String status,
                                 Long terminalId, String terminalNo, OffsetDateTime lastSeenAt,
                                 String communicationStatus, LatestPosition position) { }
    public record LatestPosition(long version, long terminalId, OffsetDateTime gpsTime, OffsetDateTime receivedAt,
                                 double lng, double lat, double speedKph, int directionDeg, boolean accOn, boolean fixValid) { }
}
