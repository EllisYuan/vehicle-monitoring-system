package com.vms.alarm;

import com.vms.protocol.PositionReport;
import com.vms.telemetry.PositionStateParticipant;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 12：平台超速的持续事件判定，挂在 07 的位置事务内执行。
 *
 * <p>连续 3 个有效实时点超过阈值成立 ACTIVE，连续 3 个点不大于阈值恢复；相邻候选点间隔
 * 超过 30 秒或规则版本变化后重新计数，但不清除已有 ACTIVE。恢复按 alarm_id 与
 * condition_state='ACTIVE' 条件更新，避免误关并发产生的新周期。报警、候选判定状态与
 * notification_outbox 与位置同事务提交，异常时由位置事务整体回滚。
 */
@Component
public class OverspeedAlarmParticipant implements PositionStateParticipant {

    static final String ALARM_TYPE = "OVERSPEED";
    static final String SOURCE = "PLATFORM";
    /** 超速只有一条适用规则，规则依据保存在事件快照，不参与 ACTIVE 唯一维度。 */
    static final String DIMENSION = "";
    static final Duration CANDIDATE_GAP = Duration.ofSeconds(30);
    static final int CONFIRM_POINTS = 3;

    private final JdbcTemplate jdbc;
    private final AlarmRuleService rules;
    private final ObjectMapper json;
    private final Clock clock;

    public OverspeedAlarmParticipant(JdbcTemplate jdbc, AlarmRuleService rules, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void apply(long vehicleId, long terminalId, long fleetId, long version,
                      PositionReport report, LocalDateTime receivedAt) {
        AlarmRuleService.ResolvedRule rule = rules.resolve(fleetId);
        if (rule == null) {
            return;
        }
        boolean over = report.speed() / 10.0 > rule.thresholdKph();
        String direction = over ? "OVER" : "UNDER";
        Candidate candidate = candidate(vehicleId);
        int count = 1;
        if (candidate != null && continuous(candidate, rule, report.time())
                && direction.equals(candidate.direction())) {
            count = candidate.count() + 1;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (candidate == null) {
            try {
                jdbc.update("""
                        INSERT INTO alarm_candidate_state (vehicle_id, alarm_type, source, business_dimension,
                            rule_id, rule_version, candidate_direction, consecutive_count, last_point_time, updated_at)
                        VALUES (?, 'OVERSPEED', 'PLATFORM', '', ?, ?, ?, ?, ?, ?)
                        """, vehicleId, rule.id(), rule.version(), direction, count, report.time(), now);
            } catch (DuplicateKeyException e) {
                // 同一车辆首次写入并发：另一事务已插入唯一键，回退为更新，保持旧 upsert 语义。
                updateCandidate(vehicleId, rule, direction, count, report.time(), now);
            }
        } else {
            updateCandidate(vehicleId, rule, direction, count, report.time(), now);
        }
        if (count >= CONFIRM_POINTS) {
            if (over) {
                activate(vehicleId, terminalId, fleetId, report, rule, now);
            } else {
                recover(vehicleId, report.time(), now);
            }
        } else if (over) {
            // 候选因间隔或方向变化重计时，既有 ACTIVE 仍延续：最后发生时间随每个超速点推进
            touchActive(vehicleId, report.time(), now);
        }
    }

    /** 已有候选行只做 UPDATE：INSERT ... ON DUPLICATE KEY UPDATE 会分配自增 id 并留下插入意向锁，
     *  多车并发更新同一张表时会互相等待而死锁。 */
    private void updateCandidate(long vehicleId, AlarmRuleService.ResolvedRule rule, String direction,
                                 int count, LocalDateTime pointTime, LocalDateTime now) {
        jdbc.update("""
                UPDATE alarm_candidate_state
                SET rule_id = ?, rule_version = ?, candidate_direction = ?, consecutive_count = ?,
                    last_point_time = ?, updated_at = ?
                WHERE vehicle_id = ? AND alarm_type = 'OVERSPEED' AND source = 'PLATFORM'
                  AND business_dimension = ''
                """, rule.id(), rule.version(), direction, count, pointTime, now, vehicleId);
    }
    private Candidate candidate(long vehicleId) {
        // 先无锁探测，仅对已存在的候选行加锁。对不存在的唯一键执行 SELECT ... FOR UPDATE 会在
        // REPEATABLE READ 下取 gap lock；多车首次并发插入同一空表区间时，各自的 gap lock 与
        // 对方的插入意向锁互相等待，实测 100 终端首波上报可触发 MySQL 死锁。
        List<Integer> exists = jdbc.query("""
                SELECT 1 FROM alarm_candidate_state
                WHERE vehicle_id = ? AND alarm_type = 'OVERSPEED' AND source = 'PLATFORM'
                  AND business_dimension = ''
                """, (rs, row) -> 1, vehicleId);
        if (exists.isEmpty()) {
            return null;
        }
        List<Candidate> rows = jdbc.query("""
                SELECT rule_id, rule_version, candidate_direction, consecutive_count, last_point_time
                FROM alarm_candidate_state
                WHERE vehicle_id = ? AND alarm_type = 'OVERSPEED' AND source = 'PLATFORM'
                  AND business_dimension = ''
                FOR UPDATE
                """, (rs, row) -> new Candidate(rs.getLong("rule_id"), rs.getLong("rule_version"),
                rs.getString("candidate_direction"), rs.getInt("consecutive_count"),
                rs.getObject("last_point_time", LocalDateTime.class)), vehicleId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 规则版本变化、点序回退或间隔超过 30 秒都会重开候选段；ACTIVE 仍由事件行保留。 */
    private boolean continuous(Candidate candidate, AlarmRuleService.ResolvedRule rule, LocalDateTime pointTime) {
        return candidate.ruleId() == rule.id() && candidate.ruleVersion() == rule.version()
                && pointTime.isAfter(candidate.lastPointTime())
                && !Duration.between(candidate.lastPointTime(), pointTime).minus(CANDIDATE_GAP).isPositive();
    }

    private void activate(long vehicleId, long terminalId, long fleetId, PositionReport report,
                          AlarmRuleService.ResolvedRule rule, LocalDateTime now) {
        List<Long> activeAlarmIds = activeAlarmIds(vehicleId);
        if (!activeAlarmIds.isEmpty()) {
            touch(activeAlarmIds.getFirst(), report.time(), now);
            return;
        }
        long alarmId;
        try {
            alarmId = insertAlarm(vehicleId, terminalId, fleetId, report, rule, currentDriver(vehicleId), now);
        } catch (DuplicateKeyException e) {
            // 唯一约束兜底：并发确认时已有同维度 ACTIVE，转为延续该事件而不是再建一条
            for (long existingId : activeAlarmIds(vehicleId)) {
                touch(existingId, report.time(), now);
            }
            return;
        }
        enqueue("alarm:created:" + alarmId, alarmId, now);
    }

    private void recover(long vehicleId, LocalDateTime pointTime, LocalDateTime now) {
        for (long alarmId : activeAlarmIds(vehicleId)) {
            int updated = jdbc.update("""
                    UPDATE alarm
                    SET condition_state = 'RECOVERED', recovered_at = ?, updated_at = ?
                    WHERE id = ? AND condition_state = 'ACTIVE'
                    """, pointTime, now, alarmId);
            if (updated > 0) {
                jdbc.update("""
                        INSERT INTO alarm_handle_log (alarm_id, change_type, from_status, to_status,
                            operator_id, remark, created_at)
                        VALUES (?, 'CONDITION', 'ACTIVE', 'RECOVERED', NULL, NULL, ?)
                        """, alarmId, now);
                enqueue("alarm:recovered:" + alarmId, alarmId, now);
            }
        }
    }

    private void touchActive(long vehicleId, LocalDateTime pointTime, LocalDateTime now) {
        for (long alarmId : activeAlarmIds(vehicleId)) {
            touch(alarmId, pointTime, now);
        }
    }

    private void touch(long alarmId, LocalDateTime pointTime, LocalDateTime now) {
        jdbc.update("""
                UPDATE alarm SET last_time = GREATEST(last_time, ?), updated_at = ?
                WHERE id = ? AND condition_state = 'ACTIVE'
                """, pointTime, now, alarmId);
    }

    private long insertAlarm(long vehicleId, long terminalId, long fleetId, PositionReport report,
                             AlarmRuleService.ResolvedRule rule, Long driverId, LocalDateTime now) {
        String snapshot = json.writeValueAsString(new RuleSnapshot(rule.id(), rule.scopeType(), rule.fleetId(),
                ALARM_TYPE, rule.thresholdKph(), rule.version()));
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO alarm (vehicle_id, terminal_id, driver_id, fleet_id_at_event, source, alarm_type,
                        business_dimension, condition_state, handle_status, rule_id, rule_version, rule_snapshot,
                        start_time, last_time, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'PLATFORM', 'OVERSPEED', '', 'ACTIVE', 'PENDING', ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, vehicleId);
            ps.setLong(2, terminalId);
            if (driverId == null) {
                ps.setNull(3, Types.BIGINT);
            } else {
                ps.setLong(3, driverId);
            }
            ps.setLong(4, fleetId);
            ps.setLong(5, rule.id());
            ps.setLong(6, rule.version());
            ps.setString(7, snapshot);
            ps.setObject(8, report.time());
            ps.setObject(9, report.time());
            ps.setObject(10, now);
            ps.setObject(11, now);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private void enqueue(String eventId, long alarmId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO notification_outbox (event_id, event_type, aggregate_id, version, status,
                    retry_at, attempts, updated_at)
                VALUES (?, 'ALARM_EVENT', ?, 1, 'PENDING', ?, 0, ?)
                """, eventId, alarmId, now, now);
    }

    private Long currentDriver(long vehicleId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT driver_id FROM vehicle_driver_binding WHERE current_vehicle_id = ?",
                Long.class, vehicleId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<Long> activeAlarmIds(long vehicleId) {
        return jdbc.queryForList("""
                SELECT id FROM alarm
                WHERE vehicle_id = ? AND alarm_type = 'OVERSPEED' AND source = 'PLATFORM'
                  AND business_dimension = '' AND condition_state = 'ACTIVE'
                FOR UPDATE
                """, Long.class, vehicleId);
    }

    record Candidate(long ruleId, long ruleVersion, String direction, int count, LocalDateTime lastPointTime) { }

    record RuleSnapshot(long ruleId, String scopeType, Long fleetId, String alarmType,
                        int thresholdKph, long ruleVersion) { }
}
