package com.vms.alarm;

import com.vms.identity.access.FleetScope;
import com.vms.identity.access.FleetScopeResolver;
import com.vms.identity.access.FleetScopeSql;
import com.vms.identity.account.UserAccount;
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

/** 12：报警推送视图按订阅者当前车队范围读取，越界事件不进入帧内容。 */
@Service
public class AlarmNotificationService {
    private static final String SELECT = """
            SELECT a.id AS alarm_id,a.vehicle_id,v.plate_no,a.fleet_id_at_event,f.fleet_name,
                   a.source,a.alarm_type,a.condition_state,a.handle_status,a.rule_version,
                   a.start_time,a.last_time,a.recovered_at
            FROM alarm a JOIN vehicle v ON v.id=a.vehicle_id JOIN fleet f ON f.id=a.fleet_id_at_event
            """;
    private final JdbcTemplate jdbc;
    private final FleetScopeResolver scopes;
    private final Clock clock;

    public AlarmNotificationService(JdbcTemplate jdbc, FleetScopeResolver scopes, Clock clock) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.clock = clock;
    }

    public AlarmEvent find(UserAccount user, long alarmId) {
        FleetScope scope = scopes.resolve(user);
        List<Object> args = new ArrayList<>();
        String where = " WHERE " + FleetScopeSql.where("a.fleet_id_at_event", scope, args);
        args.add(alarmId);
        List<AlarmEvent> rows = jdbc.query(SELECT + where + " AND a.id=?", this::view, args.toArray());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private AlarmEvent view(ResultSet rs, int row) throws SQLException {
        return new AlarmEvent(rs.getLong("alarm_id"), rs.getLong("vehicle_id"), rs.getString("plate_no"),
                rs.getLong("fleet_id_at_event"), rs.getString("fleet_name"), rs.getString("source"),
                rs.getString("alarm_type"), rs.getString("condition_state"), rs.getString("handle_status"),
                rs.getObject("rule_version", Long.class),
                offset(rs.getObject("start_time", LocalDateTime.class)),
                offset(rs.getObject("last_time", LocalDateTime.class)),
                offset(rs.getObject("recovered_at", LocalDateTime.class)));
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(ClockConfig.BUSINESS_ZONE).toOffsetDateTime();
    }

    public record AlarmEvent(long alarmId, long vehicleId, String plateNo, long fleetId, String fleetName,
                             String source, String alarmType, String conditionState, String handleStatus,
                             Long ruleVersion, OffsetDateTime startTime, OffsetDateTime lastTime,
                             OffsetDateTime recoveredAt) { }
}
