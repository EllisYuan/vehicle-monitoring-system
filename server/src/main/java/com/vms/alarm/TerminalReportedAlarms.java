package com.vms.alarm;

import com.vms.protocol.PositionReport;
import java.sql.Statement;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/** 仅由已持有 terminal / vehicle 行锁的位置事务调用，不依赖坐标是否有效。 */
@Component
public class TerminalReportedAlarms {
    // JT/T 808—2019 §8.21.1 表 25；出处和未支持范围见 evidence/14-jt808-alarm-bits.md。
    private enum Flag {
        EMERGENCY(0), OVERSPEED(1), FATIGUE_DRIVING(2), GNSS_MODULE_FAULT(4),
        GNSS_ANTENNA_DISCONNECTED(5), GNSS_ANTENNA_SHORT(6);

        final long mask;
        Flag(int bit) { mask = 1L << bit; }
        boolean set(long flags) { return (flags & mask) != 0; }
    }

    private final JdbcTemplate jdbc;

    public TerminalReportedAlarms(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void observe(long terminalId, long vehicleId, long fleetId, PositionReport report, LocalDateTime now) {
        Long previous = jdbc.queryForObject("SELECT terminal_alarm_flags FROM terminal WHERE id=?", Long.class, terminalId);
        applyFlags(terminalId, vehicleId, fleetId, previous, report, now);
        // 即使两次都没有报警也要记水位，迟到的置位帧不得开启新周期。
        jdbc.update("UPDATE terminal SET terminal_alarm_flags=?, terminal_alarm_gps_time=? WHERE id=?",
                report.alarmFlag(), report.time(), terminalId);
    }

    private void applyFlags(long terminalId, long vehicleId, long fleetId, long previous, PositionReport report,
                            LocalDateTime now) {
        String dimension = "terminal:" + terminalId;
        // 当前终端在此车的持续事件才可被它的清位恢复。同一设备重新绑定时延续旧周期。
        var active = jdbc.query("""
                SELECT id, alarm_type FROM alarm WHERE vehicle_id=? AND source='TERMINAL' AND terminal_id=?
                  AND condition_state='ACTIVE'
                """, (rs, i) -> new Active(rs.getLong(1), Flag.valueOf(rs.getString(2))), vehicleId, terminalId);
        for (Flag flag : Flag.values()) {
            Long id = active.stream().filter(a -> a.flag() == flag).map(Active::id).findFirst().orElse(null);
            boolean set = flag.set(report.alarmFlag());
            if (id != null) {
                if (set) {
                    jdbc.update("UPDATE alarm SET last_time=?, updated_at=? WHERE id=?", report.time(), now, id);
                } else {
                    recover(id, report.time(), now);
                }
            } else if (set && (flag != Flag.EMERGENCY || !flag.set(previous))) {
                create(terminalId, vehicleId, fleetId, dimension, flag, report.time(), now);
            }
        }
    }

    private void create(long terminalId, long vehicleId, long fleetId, String dimension, Flag flag,
                        LocalDateTime gpsTime, LocalDateTime now) {
        var key = new GeneratedKeyHolder();
        jdbc.update(c -> {
            var ps = c.prepareStatement("""
                    INSERT INTO alarm (terminal_id,vehicle_id,fleet_id_at_event,driver_id,source,alarm_type,
                        business_dimension,condition_state,start_time,last_time,created_at,updated_at)
                    VALUES (?,?,?,(SELECT driver_id FROM vehicle_driver_binding WHERE vehicle_id=? AND effective_from<=?
                        AND (effective_to IS NULL OR effective_to>?)), 'TERMINAL',?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, terminalId);
            ps.setLong(2, vehicleId);
            ps.setLong(3, fleetId);
            ps.setLong(4, vehicleId);
            ps.setObject(5, gpsTime);
            ps.setObject(6, gpsTime);
            ps.setString(7, flag.name());
            ps.setString(8, dimension);
            ps.setString(9, flag == Flag.EMERGENCY ? "EVENT" : "ACTIVE");
            ps.setObject(10, gpsTime);
            ps.setObject(11, gpsTime);
            ps.setObject(12, now);
            ps.setObject(13, now);
            return ps;
        }, key);
        enqueue("created", key.getKey().longValue(), now);
    }

    private void recover(long id, LocalDateTime gpsTime, LocalDateTime now) {
        int changed = jdbc.update("""
                UPDATE alarm SET condition_state='RECOVERED',recovered_at=?,updated_at=?
                WHERE id=? AND condition_state='ACTIVE'
                """, gpsTime, now, id);
        if (changed == 1) {
            jdbc.update("""
                    INSERT INTO alarm_handle_log (alarm_id,change_type,from_status,to_status,created_at)
                    VALUES (?,'CONDITION','ACTIVE','RECOVERED',?)
                    """, id, now);
            enqueue("recovered", id, now);
        }
    }

    private void enqueue(String action, long id, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO notification_outbox (event_id,event_type,aggregate_id,version,status,retry_at,attempts,updated_at)
                VALUES (?,'ALARM_EVENT',?,1,'PENDING',?,0,?)
                """, "alarm:" + action + ":" + id, id, now, now);
    }

    private record Active(Long id, Flag flag) { }
}
