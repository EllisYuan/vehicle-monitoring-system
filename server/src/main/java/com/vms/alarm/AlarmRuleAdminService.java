package com.vms.alarm;

import com.vms.audit.AuditService;
import com.vms.identity.access.FleetScope;
import com.vms.identity.access.FleetScopeResolver;
import com.vms.identity.access.FleetScopeSql;
import com.vms.identity.account.UserAccount;
import com.vms.identity.security.ForbiddenException;
import com.vms.infrastructure.web.NotFoundException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 12：全局默认与车队覆盖两级超速规则的维护；每次修改递增版本，事件保留修改时快照。
 */
@Service
public class AlarmRuleAdminService {

    static final String ALARM_TYPE = "OVERSPEED";
    static final String SCOPE_GLOBAL = "GLOBAL";
    static final String SCOPE_FLEET = "FLEET";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FleetScopeResolver scopeResolver;
    private final AuditService auditService;
    private final Clock clock;

    public AlarmRuleAdminService(JdbcTemplate jdbc, ObjectMapper json, FleetScopeResolver scopeResolver,
                                 AuditService auditService, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.scopeResolver = scopeResolver;
        this.auditService = auditService;
        this.clock = clock;
    }

    public OverviewView overview(UserAccount user) {
        FleetScope scope = scopeResolver.resolve(user);
        List<RuleView> globals = jdbc.query("""
                SELECT id, scope_type, fleet_id, alarm_type, parameters, enabled, version, updated_at
                FROM alarm_rule WHERE alarm_type = 'OVERSPEED' AND scope_type = 'GLOBAL'
                """, (rs, row) -> view(rs, null));
        List<Object> args = new ArrayList<>();
        String where = FleetScopeSql.where("r.fleet_id", scope, args);
        List<RuleView> overrides = jdbc.query("""
                SELECT r.id, r.scope_type, r.fleet_id, r.alarm_type, r.parameters, r.enabled, r.version,
                    r.updated_at, f.fleet_name
                FROM alarm_rule r JOIN fleet f ON f.id = r.fleet_id
                WHERE r.alarm_type = 'OVERSPEED' AND r.scope_type = 'FLEET' AND %s
                ORDER BY f.fleet_code
                """.formatted(where), (rs, row) -> view(rs, rs.getString("fleet_name")), args.toArray());
        return new OverviewView(globals.isEmpty() ? null : globals.getFirst(), overrides);
    }

    @Transactional
    public RuleView updateGlobal(UserAccount actor, int thresholdKph) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<RuleRow> rows = lockGlobal();
        long ruleId;
        if (rows.isEmpty()) {
            ruleId = insert(SCOPE_GLOBAL, null, thresholdKph, now);
        } else {
            ruleId = rows.getFirst().id();
            jdbc.update("""
                    UPDATE alarm_rule SET parameters = ?, enabled = 1, version = version + 1, updated_at = ?
                    WHERE id = ?
                    """, parameters(thresholdKph), now, ruleId);
        }
        RuleView view = get(ruleId);
        auditService.record(AuditService.CATEGORY_ALARM, "UPDATE_GLOBAL", "alarm-rule:" + ruleId,
                AuditService.RESULT_SUCCESS, actor.getId(), actor.getUsername(), null,
                "alarmType=" + ALARM_TYPE + ", thresholdKph=" + thresholdKph + ", version=" + view.version());
        return view;
    }

    @Transactional
    public RuleView upsertFleetOverride(UserAccount actor, long fleetId, int thresholdKph) {
        requireFleetInScope(actor, fleetId);
        requireFleetExists(fleetId);
        LocalDateTime now = LocalDateTime.now(clock);
        List<RuleRow> rows = lockFleet(fleetId);
        long ruleId;
        if (rows.isEmpty()) {
            ruleId = insert(SCOPE_FLEET, fleetId, thresholdKph, now);
        } else {
            ruleId = rows.getFirst().id();
            jdbc.update("""
                    UPDATE alarm_rule SET parameters = ?, enabled = 1, version = version + 1, updated_at = ?
                    WHERE id = ?
                    """, parameters(thresholdKph), now, ruleId);
        }
        RuleView view = get(ruleId);
        auditService.record(AuditService.CATEGORY_ALARM, "UPSERT_FLEET_OVERRIDE", "alarm-rule:" + ruleId,
                AuditService.RESULT_SUCCESS, actor.getId(), actor.getUsername(), null,
                "alarmType=" + ALARM_TYPE + ", fleetId=" + fleetId + ", thresholdKph=" + thresholdKph
                        + ", version=" + view.version());
        return view;
    }

    @Transactional
    public void deleteFleetOverride(UserAccount actor, long fleetId) {
        requireFleetInScope(actor, fleetId);
        int deleted = jdbc.update("""
                DELETE FROM alarm_rule
                WHERE alarm_type = 'OVERSPEED' AND scope_type = 'FLEET' AND fleet_id = ?
                """, fleetId);
        if (deleted > 0) {
            auditService.record(AuditService.CATEGORY_ALARM, "DELETE_FLEET_OVERRIDE", "alarm-rule:fleet:" + fleetId,
                    AuditService.RESULT_SUCCESS, actor.getId(), actor.getUsername(), null,
                    "alarmType=" + ALARM_TYPE + ", fleetId=" + fleetId);
        }
    }

    private List<RuleRow> lockGlobal() {
        return jdbc.query("""
                SELECT id FROM alarm_rule
                WHERE alarm_type = 'OVERSPEED' AND scope_type = 'GLOBAL'
                FOR UPDATE
                """, (rs, row) -> new RuleRow(rs.getLong("id")));
    }

    private List<RuleRow> lockFleet(long fleetId) {
        return jdbc.query("""
                SELECT id FROM alarm_rule
                WHERE alarm_type = 'OVERSPEED' AND scope_type = 'FLEET' AND fleet_id = ?
                FOR UPDATE
                """, (rs, row) -> new RuleRow(rs.getLong("id")), fleetId);
    }

    private long insert(String scopeType, Long fleetId, int thresholdKph, LocalDateTime now) {
        org.springframework.jdbc.support.KeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO alarm_rule (scope_type, fleet_id, alarm_type, parameters, enabled, version,
                        created_at, updated_at)
                    VALUES (?, ?, 'OVERSPEED', ?, 1, 1, ?, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, scopeType);
            if (fleetId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, fleetId);
            }
            ps.setString(3, parameters(thresholdKph));
            ps.setObject(4, now);
            ps.setObject(5, now);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private RuleView get(long ruleId) {
        return jdbc.queryForObject("""
                SELECT r.id, r.scope_type, r.fleet_id, r.alarm_type, r.parameters, r.enabled, r.version,
                    r.updated_at, f.fleet_name
                FROM alarm_rule r LEFT JOIN fleet f ON f.id = r.fleet_id
                WHERE r.id = ?
                """, (rs, row) -> view(rs, rs.getString("fleet_name")), ruleId);
    }

    private void requireFleetInScope(UserAccount actor, long fleetId) {
        if (!scopeResolver.resolve(actor).contains(fleetId)) {
            throw new ForbiddenException();
        }
    }

    private void requireFleetExists(long fleetId) {
        if (jdbc.queryForList("SELECT id FROM fleet WHERE id = ?", Long.class, fleetId).isEmpty()) {
            throw new NotFoundException("FLEET_NOT_FOUND", "车队不存在");
        }
    }

    private String parameters(int thresholdKph) {
        return json.writeValueAsString(java.util.Map.of("thresholdKph", thresholdKph));
    }

    private RuleView view(java.sql.ResultSet rs, String fleetName) throws java.sql.SQLException {
        return new RuleView(rs.getLong("id"), rs.getString("scope_type"),
                rs.getObject("fleet_id", Long.class), fleetName, rs.getString("alarm_type"),
                json.readTree(rs.getString("parameters")).path("thresholdKph").asInt(),
                rs.getBoolean("enabled"), rs.getLong("version"),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private record RuleRow(long id) { }

    public record RuleView(long id, String scopeType, Long fleetId, String fleetName, String alarmType,
                           int thresholdKph, boolean enabled, long version, LocalDateTime updatedAt) { }

    public record OverviewView(RuleView global, List<RuleView> fleetOverrides) { }
}
