package com.vms.alarm;

import com.vms.protocol.PositionReport;
import com.vms.telemetry.PositionQualityParticipant;
import com.vms.telemetry.PositionStateParticipant;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/**
 * 14：实时定位无效的持续事件判定，挂在 07 的位置事务内执行。
 *
 * <p>连续 3 个实时无效点成立 ACTIVE，连续 3 个有效实时点恢复；无报警规则，rule_id、
 * rule_version 与 rule_snapshot 保持 NULL。无效点不进入超速与围栏判定，也不更新有效位置
 * 快照；有效点只用于恢复计数，不推进报警最后发生时间。
 */
@Component
public class PositionInvalidAlarmParticipant implements PositionStateParticipant, PositionQualityParticipant {

    static final String ALARM_TYPE = "POSITION_INVALID";
    static final String SOURCE = "PLATFORM";
    static final String DIMENSION = "";
    static final int CONFIRM_POINTS = 3;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PositionInvalidAlarmParticipant(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 有效实时点：只推进恢复计数，已有 ACTIVE 不在恢复确认前改变。 */
    @Override
    public void apply(long vehicleId, long terminalId, long fleetId, long version,
                      PositionReport report, LocalDateTime receivedAt) {
        observePosition(vehicleId, terminalId, fleetId, report);
    }

    /** 无效实时点：推进成立计数与已有 ACTIVE 的最后发生时间。 */
    @Override
    public void observe(long vehicleId, long terminalId, long fleetId,
                        PositionReport report, LocalDateTime receivedAt) {
        observePosition(vehicleId, terminalId, fleetId, report);
    }

    private void observePosition(long vehicleId, long terminalId, long fleetId, PositionReport report) {
        String direction = report.fixValid() ? "VALID" : "INVALID";
        Candidate candidate = candidate(vehicleId);
        int count = 1;
        if (candidate != null && direction.equals(candidate.direction())
                && report.time().isAfter(candidate.lastPointTime())) {
            count = candidate.count() + 1;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (candidate == null) {
            try {
                jdbc.update("""
                        INSERT INTO alarm_candidate_state (vehicle_id, alarm_type, source, business_dimension,
                            rule_id, rule_version, candidate_direction, consecutive_count, last_point_time, updated_at)
                        VALUES (?, 'POSITION_INVALID', 'PLATFORM', '', NULL, NULL, ?, ?, ?, ?)
                        """, vehicleId, direction, count, report.time(), now);
            } catch (DuplicateKeyException e) {
                // 同一车辆首次写入并发：另一事务已插入唯一键，回退为更新，保持旧 upsert 语义。
                updateCandidate(vehicleId, direction, count, report.time(), now);
            }
        } else {
            updateCandidate(vehicleId, direction, count, report.time(), now);
        }
        if ("INVALID".equals(direction)) {
            if (count >= CONFIRM_POINTS) {
                activate(vehicleId, terminalId, fleetId, report, now);
            } else {
                // 候选因方向变化重计时，既有 ACTIVE 仍延续
                touchActive(vehicleId, report.time(), now);
            }
        } else if (count >= CONFIRM_POINTS) {
            recover(vehicleId, report.time(), now);
        }
    }

    /** 已有候选行只做 UPDATE：INSERT ... ON DUPLICATE KEY UPDATE 会分配自增 id 并留下插入意向锁，
     *  多车并发更新同一张表时会互相等待而死锁。 */
    private void updateCandidate(long vehicleId, String direction, int count,
                                 LocalDateTime pointTime, LocalDateTime now) {
        jdbc.update("""
                UPDATE alarm_candidate_state
                SET rule_id = NULL, rule_version = NULL, candidate_direction = ?,
                    consecutive_count = ?, last_point_time = ?, updated_at = ?
                WHERE vehicle_id = ? AND alarm_type = 'POSITION_INVALID' AND source = 'PLATFORM'
                  AND business_dimension = ''
                """, direction, count, pointTime, now, vehicleId);
    }
    private Candidate candidate(long vehicleId) {
        // 先无锁探测，仅对已存在的候选行加锁；避免对不存在的唯一键取 gap lock，
        // 与并发首次 INSERT 的插入意向锁互相等待而死锁（同 OverspeedAlarmParticipant）。
        List<Integer> exists = jdbc.query("""
                SELECT 1 FROM alarm_candidate_state
                WHERE vehicle_id = ? AND alarm_type = 'POSITION_INVALID' AND source = 'PLATFORM'
                  AND business_dimension = ''
                """, (rs, row) -> 1, vehicleId);
        if (exists.isEmpty()) {
            return null;
        }
        List<Candidate> rows = jdbc.query("""
                SELECT candidate_direction, consecutive_count, last_point_time
                FROM alarm_candidate_state
                WHERE vehicle_id = ? AND alarm_type = 'POSITION_INVALID' AND source = 'PLATFORM'
                  AND business_dimension = ''
                FOR UPDATE
                """, (rs, row) -> new Candidate(rs.getString("candidate_direction"),
                rs.getInt("consecutive_count"), rs.getObject("last_point_time", LocalDateTime.class)), vehicleId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void activate(long vehicleId, long terminalId, long fleetId, PositionReport report,
                          LocalDateTime now) {
        List<Long> activeAlarmIds = activeAlarmIds(vehicleId);
        if (!activeAlarmIds.isEmpty()) {
            touch(activeAlarmIds.getFirst(), report.time(), now);
            return;
        }
        long alarmId;
        try {
            alarmId = insertAlarm(vehicleId, terminalId, fleetId, report, currentDriver(vehicleId), now);
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
                             Long driverId, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO alarm (vehicle_id, terminal_id, driver_id, fleet_id_at_event, source, alarm_type,
                        business_dimension, condition_state, handle_status, start_time, last_time, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'PLATFORM', 'POSITION_INVALID', '', 'ACTIVE', 'PENDING', ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, vehicleId);
            ps.setLong(2, terminalId);
            if (driverId == null) {
                ps.setNull(3, Types.BIGINT);
            } else {
                ps.setLong(3, driverId);
            }
            ps.setLong(4, fleetId);
            ps.setObject(5, report.time());
            ps.setObject(6, report.time());
            ps.setObject(7, now);
            ps.setObject(8, now);
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
                WHERE vehicle_id = ? AND alarm_type = 'POSITION_INVALID' AND source = 'PLATFORM'
                  AND business_dimension = '' AND condition_state = 'ACTIVE'
                FOR UPDATE
                """, Long.class, vehicleId);
    }

    record Candidate(String direction, int count, LocalDateTime lastPointTime) { }
}