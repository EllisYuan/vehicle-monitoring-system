package com.vms.alarm;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 超速规则解析：车队覆盖优先于全局默认；只有启用的规则参与判定。 */
@Service
public class AlarmRuleService {

    static final String ALARM_TYPE_OVERSPEED = "OVERSPEED";
    static final String SCOPE_GLOBAL = "GLOBAL";
    static final String SCOPE_FLEET = "FLEET";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AlarmRuleService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 解析该车队当前适用的超速规则；无启用规则时返回 null。 */
    public ResolvedRule resolve(long fleetId) {
        List<ResolvedRule> rows = jdbc.query("""
                SELECT id, scope_type, fleet_id, parameters, version
                FROM alarm_rule
                WHERE alarm_type = 'OVERSPEED' AND enabled = 1
                  AND (scope_type = 'GLOBAL' OR (scope_type = 'FLEET' AND fleet_id = ?))
                ORDER BY CASE WHEN scope_type = 'FLEET' THEN 0 ELSE 1 END
                LIMIT 1
                """, (rs, row) -> new ResolvedRule(rs.getLong("id"), rs.getString("scope_type"),
                rs.getObject("fleet_id", Long.class),
                json.readTree(rs.getString("parameters")).path("thresholdKph").asInt(),
                rs.getLong("version")), fleetId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public record ResolvedRule(long id, String scopeType, Long fleetId, int thresholdKph, long version) {
    }
}
