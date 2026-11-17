package com.vms.alarm;

import com.vms.identity.access.FleetScope;
import com.vms.identity.access.FleetScopeResolver;
import com.vms.identity.access.FleetScopeSql;
import com.vms.identity.account.UserAccount;
import com.vms.identity.security.ForbiddenException;
import com.vms.identity.security.RoleCode;
import com.vms.infrastructure.ClockConfig;
import com.vms.infrastructure.web.NotFoundException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 13：报警列表与详情读取；数据范围在 SQL 与服务层双重限制。 */
@Service
public class AlarmQueryService {

    private static final String LIST_SELECT = """
            SELECT a.id,a.vehicle_id,v.plate_no,a.fleet_id_at_event,f.fleet_name,a.source,a.alarm_type,
                   a.condition_state,a.handle_status,a.driver_id,a.rule_version,a.start_time,a.last_time,a.recovered_at
            FROM alarm a JOIN vehicle v ON v.id=a.vehicle_id JOIN fleet f ON f.id=a.fleet_id_at_event
            """;

    private static final String COUNT_SELECT = "SELECT count(*) FROM alarm a";

    private static final String HANDLING_SELECT = """
            SELECT l.id,l.alarm_id,v.plate_no,a.fleet_id_at_event,f.fleet_name,l.change_type,l.from_status,
                   l.to_status,l.operator_id,u.username,l.remark,l.created_at
            FROM alarm_handle_log l JOIN alarm a ON a.id=l.alarm_id
            JOIN vehicle v ON v.id=a.vehicle_id JOIN fleet f ON f.id=a.fleet_id_at_event
            LEFT JOIN sys_user u ON u.id=l.operator_id
            """;

    private static final String HANDLING_COUNT_SELECT =
            "SELECT count(*) FROM alarm_handle_log l JOIN alarm a ON a.id=l.alarm_id";

    private static final String DETAIL_SELECT = """
            SELECT a.id,a.vehicle_id,v.plate_no,a.fleet_id_at_event,f.fleet_name,a.source,a.alarm_type,
                   a.condition_state,a.handle_status,a.driver_id,a.rule_id,a.rule_version,a.rule_snapshot,
                   a.start_time,a.last_time,a.recovered_at,a.created_at,a.updated_at
            FROM alarm a JOIN vehicle v ON v.id=a.vehicle_id JOIN fleet f ON f.id=a.fleet_id_at_event
            """;

    private final JdbcTemplate jdbc;
    private final FleetScopeResolver scopes;
    private final ObjectMapper json;

    public AlarmQueryService(JdbcTemplate jdbc, FleetScopeResolver scopes, ObjectMapper json) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.json = json;
    }

    public List<AlarmRow> list(UserAccount user, Long fleetId) {
        List<Object> args = new ArrayList<>();
        String where = filter(scope(user, fleetId), fleetId, args);
        String sql = LIST_SELECT + " WHERE " + where + " ORDER BY a.start_time DESC, a.id DESC";
        return jdbc.query(sql, this::row, args.toArray());
    }

    /** 导出前计数，与列表使用同一数据范围与筛选条件。 */
    public long count(UserAccount user, Long fleetId) {
        List<Object> args = new ArrayList<>();
        String where = filter(scope(user, fleetId), fleetId, args);
        Long total = jdbc.queryForObject(COUNT_SELECT + " WHERE " + where, Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    /** 处置记录，与处置页面使用同一数据范围与车队筛选。 */
    public List<HandlingRow> handlingRecords(UserAccount user, Long fleetId) {
        List<Object> args = new ArrayList<>();
        String where = filter(scope(user, fleetId), fleetId, args);
        String sql = HANDLING_SELECT + " WHERE " + where + " ORDER BY l.created_at, l.id";
        return jdbc.query(sql, this::handlingRow, args.toArray());
    }

    public long countHandlingRecords(UserAccount user, Long fleetId) {
        List<Object> args = new ArrayList<>();
        String where = filter(scope(user, fleetId), fleetId, args);
        Long total = jdbc.queryForObject(HANDLING_COUNT_SELECT + " WHERE " + where, Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    private FleetScope scope(UserAccount user, Long fleetId) {
        FleetScope scope = scopes.resolve(user);
        if (fleetId != null && !scope.contains(fleetId)) {
            throw new ForbiddenException();
        }
        return scope;
    }

    private String filter(FleetScope scope, Long fleetId, List<Object> args) {
        StringBuilder where = new StringBuilder(FleetScopeSql.where("a.fleet_id_at_event", scope, args));
        if (fleetId != null) {
            where.append(" AND a.fleet_id_at_event=?");
            args.add(fleetId);
        }
        return where.toString();
    }

    public AlarmDetail detail(UserAccount user, long alarmId) {
        FleetScope scope = scopes.resolve(user);
        List<AlarmBase> rows = jdbc.query(DETAIL_SELECT + " WHERE a.id=?", this::base, alarmId);
        if (rows.isEmpty()) {
            throw new NotFoundException("ALARM_NOT_FOUND", "报警不存在");
        }
        AlarmBase alarm = rows.getFirst();
        if (!scope.contains(alarm.fleetId())) {
            throw new ForbiddenException();
        }
        RoleCode role = RoleCode.parse(user.getRoleCode()).orElseThrow();
        DriverView driver = alarm.driverId() == null ? null : driver(alarm.driverId(), role);
        List<HandleLog> allHistory = history(alarmId);
        List<HandleLog> handleHistory = allHistory.stream()
                .filter(log -> "HANDLE".equals(log.changeType())).toList();
        List<HandleLog> conditionHistory = allHistory.stream()
                .filter(log -> "CONDITION".equals(log.changeType())).toList();
        return new AlarmDetail(alarm.id(), alarm.vehicleId(), alarm.plateNo(), alarm.fleetId(),
                alarm.fleetName(), alarm.source(), alarm.alarmType(), alarm.conditionState(),
                alarm.handleStatus(), alarm.driverId(), driver, alarm.ruleId(), alarm.ruleVersion(),
                alarm.ruleSnapshot(), alarm.startTime(), alarm.lastTime(), alarm.recoveredAt(),
                position(alarm.vehicleId(), alarm.startTime()), handleHistory, conditionHistory,
                alarm.createdAt(), alarm.updatedAt());
    }

    private AlarmRow row(ResultSet rs, int index) throws SQLException {
        return new AlarmRow(rs.getLong("id"), rs.getLong("vehicle_id"), rs.getString("plate_no"),
                rs.getLong("fleet_id_at_event"), rs.getString("fleet_name"), rs.getString("source"),
                rs.getString("alarm_type"), rs.getString("condition_state"), rs.getString("handle_status"),
                rs.getObject("driver_id", Long.class), rs.getObject("rule_version", Long.class),
                offset(rs.getObject("start_time", LocalDateTime.class)),
                offset(rs.getObject("last_time", LocalDateTime.class)),
                offset(rs.getObject("recovered_at", LocalDateTime.class)));
    }

    private HandlingRow handlingRow(ResultSet rs, int index) throws SQLException {
        return new HandlingRow(rs.getLong("id"), rs.getLong("alarm_id"), rs.getString("plate_no"),
                rs.getLong("fleet_id_at_event"), rs.getString("fleet_name"), rs.getString("change_type"),
                rs.getString("from_status"), rs.getString("to_status"), rs.getObject("operator_id", Long.class),
                rs.getString("username"), rs.getString("remark"),
                offset(rs.getObject("created_at", LocalDateTime.class)));
    }

    private AlarmBase base(ResultSet rs, int index) throws SQLException {
        String snapshot = rs.getString("rule_snapshot");
        JsonNode snapshotNode = snapshot == null || snapshot.isBlank() ? null : json.readTree(snapshot);
        return new AlarmBase(rs.getLong("id"), rs.getLong("vehicle_id"), rs.getString("plate_no"),
                rs.getLong("fleet_id_at_event"), rs.getString("fleet_name"), rs.getString("source"),
                rs.getString("alarm_type"), rs.getString("condition_state"), rs.getString("handle_status"),
                rs.getObject("driver_id", Long.class), rs.getObject("rule_id", Long.class),
                rs.getObject("rule_version", Long.class), snapshotNode,
                offset(rs.getObject("start_time", LocalDateTime.class)),
                offset(rs.getObject("last_time", LocalDateTime.class)),
                offset(rs.getObject("recovered_at", LocalDateTime.class)),
                offset(rs.getObject("created_at", LocalDateTime.class)),
                offset(rs.getObject("updated_at", LocalDateTime.class)));
    }

    private DriverView driver(long driverId, RoleCode role) {
        List<DriverView> rows = jdbc.query(
                "SELECT id,name,phone FROM driver WHERE id=?", (rs, index) -> new DriverView(
                        rs.getLong("id"), rs.getString("name"), maskPhone(rs.getString("phone"), role)), driverId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private PositionView position(long vehicleId, OffsetDateTime startTime) {
        LocalDateTime trigger = startTime.atZoneSameInstant(ClockConfig.BUSINESS_ZONE).toLocalDateTime();
        List<PositionView> rows = jdbc.query("""
                SELECT lng,lat,speed,gps_time FROM track_point
                WHERE vehicle_id=? AND gps_time<=? ORDER BY gps_time DESC,id DESC LIMIT 1
                """, (rs, index) -> new PositionView(rs.getBigDecimal("lng"), rs.getBigDecimal("lat"),
                rs.getObject("speed", Double.class),
                offset(rs.getObject("gps_time", LocalDateTime.class))), vehicleId, trigger);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<HandleLog> history(long alarmId) {
        return jdbc.query("""
                SELECT l.id,l.change_type,l.from_status,l.to_status,l.operator_id,u.username,l.remark,l.created_at
                FROM alarm_handle_log l LEFT JOIN sys_user u ON u.id=l.operator_id
                WHERE l.alarm_id=? ORDER BY l.created_at,l.id
                """, (rs, index) -> new HandleLog(rs.getLong("id"), rs.getString("change_type"),
                rs.getString("from_status"), rs.getString("to_status"), rs.getObject("operator_id", Long.class),
                rs.getString("username"), rs.getString("remark"),
                offset(rs.getObject("created_at", LocalDateTime.class))), alarmId);
    }

    static String maskPhone(String phone, RoleCode role) {
        if (role != RoleCode.FLEET_LEADER || phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(ClockConfig.BUSINESS_ZONE).toOffsetDateTime();
    }

    public record AlarmRow(long id, long vehicleId, String plateNo, long fleetId, String fleetName,
                           String source, String alarmType, String conditionState, String handleStatus,
                           Long driverId, Long ruleVersion, OffsetDateTime startTime, OffsetDateTime lastTime,
                           OffsetDateTime recoveredAt) { }

    public record AlarmDetail(long id, long vehicleId, String plateNo, long fleetId, String fleetName,
                              String source, String alarmType, String conditionState, String handleStatus,
                              Long driverId, DriverView driver, Long ruleId, Long ruleVersion,
                              JsonNode ruleSnapshot, OffsetDateTime startTime, OffsetDateTime lastTime,
                              OffsetDateTime recoveredAt, PositionView position, List<HandleLog> handleHistory,
                              List<HandleLog> conditionHistory, OffsetDateTime createdAt,
                              OffsetDateTime updatedAt) { }

    public record DriverView(long id, String name, String phone) { }

    public record PositionView(BigDecimal lng, BigDecimal lat, Double speedKph, OffsetDateTime gpsTime) { }

    public record HandleLog(long id, String changeType, String fromStatus, String toStatus, Long operatorId,
                            String operatorName, String remark, OffsetDateTime createdAt) { }

    public record HandlingRow(long id, long alarmId, String plateNo, long fleetId, String fleetName,
                              String changeType, String fromStatus, String toStatus, Long operatorId,
                              String operatorName, String remark, OffsetDateTime createdAt) { }

    private record AlarmBase(long id, long vehicleId, String plateNo, long fleetId, String fleetName,
                             String source, String alarmType, String conditionState, String handleStatus,
                             Long driverId, Long ruleId, Long ruleVersion, JsonNode ruleSnapshot,
                             OffsetDateTime startTime, OffsetDateTime lastTime, OffsetDateTime recoveredAt,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
}
