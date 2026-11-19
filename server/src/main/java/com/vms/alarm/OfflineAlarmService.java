package com.vms.alarm;

import com.vms.infrastructure.EffectiveTime;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 14：离线报警判定与通信恢复。
 *
 * <p>判定依据是有效通信时间 last_seen_at，而不是当前 TCP 连接：已启用、已鉴权且仍绑定的
 * 终端超过 180 秒无有效通信成立 OFFLINE；未接入、合法注销（last_logout_at 不早于
 * last_seen_at）、停用与解绑不参与判定。应用启动后的 180 秒宽限内不扫描，避免重启误报。
 * 有效通信到达（鉴权、心跳、终端应答、位置接收）在同一事务内恢复既有 ACTIVE，并写
 * CONDITION 日志（operator 与 remark 为空），与人工处置的 HANDLE 日志分开记录；
 * 停用、解绑只标注 scope_changed_at，保留 ACTIVE 与最后发生时间。
 */
@Service
public class OfflineAlarmService {

    static final String ALARM_TYPE = "OFFLINE";
    static final String SOURCE = "PLATFORM";
    static final long OFFLINE_SECONDS = 180;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final LocalDateTime startedAt;

    public OfflineAlarmService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.startedAt = EffectiveTime.now(clock);
    }

    /** 扫描一次失联终端；由调度器按配置间隔调用，测试直接调用同一入口推进判定。 */
    @Scheduled(fixedDelayString = "${vms.alarm.offline-scan-interval-ms:10000}",
            initialDelayString = "${vms.alarm.offline-scan-initial-delay-ms:180000}")
    @Transactional
    public void scan() {
        LocalDateTime now = EffectiveTime.now(clock);
        if (now.isBefore(startedAt.plusSeconds(OFFLINE_SECONDS))) {
            return;
        }
        LocalDateTime deadline = now.minusSeconds(OFFLINE_SECONDS);
        List<StaleTerminal> stale = jdbc.query("""
                SELECT t.id AS terminal_id, t.last_seen_at, v.id AS vehicle_id, v.fleet_id
                FROM terminal t
                JOIN terminal_vehicle_binding b ON b.current_terminal_id = t.id
                JOIN vehicle v ON v.id = b.vehicle_id
                WHERE t.status = 'ENABLED' AND v.status = 'ENABLED'
                  AND t.ever_authenticated = TRUE AND t.last_seen_at IS NOT NULL
                  AND t.last_seen_at <= ?
                  AND (t.last_logout_at IS NULL OR t.last_logout_at < t.last_seen_at)
                  AND NOT EXISTS (SELECT 1 FROM alarm a WHERE a.terminal_id = t.id
                      AND a.alarm_type = 'OFFLINE' AND a.condition_state = 'ACTIVE')
                """, (rs, row) -> new StaleTerminal(rs.getLong("terminal_id"),
                rs.getObject("last_seen_at", LocalDateTime.class),
                rs.getLong("vehicle_id"), rs.getLong("fleet_id")), deadline);
        for (StaleTerminal terminal : stale) {
            activate(terminal, now);
        }
    }

    /** 有效通信到达时恢复该终端的 ACTIVE 离线事件；由通信路径在同一事务内调用。 */
    public void recover(long terminalId, LocalDateTime at) {
        List<Long> alarmIds = jdbc.queryForList("""
                SELECT id FROM alarm WHERE terminal_id = ? AND alarm_type = 'OFFLINE'
                  AND condition_state = 'ACTIVE' FOR UPDATE
                """, Long.class, terminalId);
        for (long alarmId : alarmIds) {
            int updated = jdbc.update("""
                    UPDATE alarm SET condition_state = 'RECOVERED', recovered_at = ?, updated_at = ?
                    WHERE id = ? AND condition_state = 'ACTIVE'
                    """, at, at, alarmId);
            if (updated > 0) {
                jdbc.update("""
                        INSERT INTO alarm_handle_log (alarm_id, change_type, from_status, to_status,
                            operator_id, remark, created_at)
                        VALUES (?, 'CONDITION', 'ACTIVE', 'RECOVERED', NULL, NULL, ?)
                        """, alarmId, at);
                enqueue("alarm:recovered:" + alarmId, alarmId, at);
            }
        }
    }

    /** 停用、解绑等管理操作只标注监控范围变化，不写 recovered_at。 */
    public void markScopeChangedByTerminal(long terminalId, LocalDateTime at) {
        markScopeChanged("terminal_id = ?", terminalId, at);
    }

    public void markScopeChangedByVehicle(long vehicleId, LocalDateTime at) {
        markScopeChanged("vehicle_id = ?", vehicleId, at);
    }

    public void markScopeChangedByVehicleTerminal(long vehicleId, long terminalId, LocalDateTime at) {
        jdbc.update("""
                UPDATE alarm SET scope_changed_at = ?, updated_at = ?
                WHERE vehicle_id = ? AND terminal_id = ? AND condition_state = 'ACTIVE'
                  AND alarm_type IN ('OFFLINE', 'POSITION_INVALID')
                """, at, at, vehicleId, terminalId);
    }

    private void markScopeChanged(String filter, long id, LocalDateTime at) {
        jdbc.update("UPDATE alarm SET scope_changed_at = ?, updated_at = ? WHERE " + filter
                        + " AND condition_state = 'ACTIVE' AND alarm_type IN ('OFFLINE', 'POSITION_INVALID')",
                at, at, id);
    }

    private void activate(StaleTerminal terminal, LocalDateTime now) {
        LocalDateTime offlineSince = terminal.lastSeenAt().plusSeconds(OFFLINE_SECONDS);
        long alarmId;
        try {
            alarmId = insertAlarm(terminal, offlineSince, now);
        } catch (DuplicateKeyException concurrent) {
            // 唯一维度兜底：并发扫描已建立同维度 ACTIVE，本周期不重复建事件
            return;
        }
        enqueue("alarm:created:" + alarmId, alarmId, now);
    }

    private long insertAlarm(StaleTerminal terminal, LocalDateTime offlineSince, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO alarm (vehicle_id, terminal_id, driver_id, fleet_id_at_event, source, alarm_type,
                        business_dimension, condition_state, handle_status, start_time, last_time, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'PLATFORM', 'OFFLINE', '', 'ACTIVE', 'PENDING', ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, terminal.vehicleId());
            ps.setLong(2, terminal.terminalId());
            Long driverId = currentDriver(terminal.vehicleId());
            if (driverId == null) {
                ps.setNull(3, Types.BIGINT);
            } else {
                ps.setLong(3, driverId);
            }
            ps.setLong(4, terminal.fleetId());
            ps.setObject(5, offlineSince);
            ps.setObject(6, offlineSince);
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

    private record StaleTerminal(long terminalId, LocalDateTime lastSeenAt, long vehicleId, long fleetId) { }
}