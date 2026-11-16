package com.vms.telemetry;

import com.vms.access.TerminalConnectionRegistry;
import com.vms.protocol.PositionReport;
import com.vms.track.TrackRetentionPolicy;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PositionIngestService {
    static final long FUTURE_TOLERANCE_MILLIS = 30_000;
    static final long REALTIME_MAX_AGE_MILLIS = 60_000;

    private final JdbcTemplate jdbc;
    private final TerminalConnectionRegistry connections;
    private final List<PositionStateParticipant> participants;
    private final TrackRetentionPolicy retention;

    public PositionIngestService(JdbcTemplate jdbc, TerminalConnectionRegistry connections,
                                 List<PositionStateParticipant> participants, TrackRetentionPolicy retention) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.participants = participants;
        this.retention = retention;
    }

    @Transactional
    public void accept(TerminalConnectionRegistry.Session session, byte[] body, LocalDateTime receivedAt) {
        long terminalId = session.terminalId();
        List<String> terminals = jdbc.queryForList("SELECT status FROM terminal WHERE id = ? FOR UPDATE",
                String.class, terminalId);
        if (!connections.isCurrent(session)) {
            throw new IllegalStateException("Terminal session is no longer admissible");
        }
        RawPositionFrame frame = RawPositionFrame.parse(body);
        if (frame.problem() == RawPositionFrame.Problem.INVALID_TIME) {
            recordAnomaly(terminalId, null, null, null, receivedAt, "INVALID_TIME", body);
            return;
        }
        if (frame.problem() == RawPositionFrame.Problem.COORDINATE_OUT_OF_RANGE) {
            recordAnomaly(terminalId, null, null, frame.gpsTime(), receivedAt, "COORDINATE_OUT_OF_RANGE", body);
            return;
        }
        PositionReport report;
        try {
            report = frame.toReport();
        } catch (IllegalArgumentException e) {
            recordAnomaly(terminalId, null, null, frame.gpsTime(), receivedAt, "INVALID_POSITION_FIELDS", body);
            return;
        }
        Long vehicleId = null;
        Long fleetId = null;
        List<Long> vehicles = jdbc.queryForList(
                "SELECT vehicle_id FROM terminal_vehicle_binding WHERE current_terminal_id = ?",
                Long.class, terminalId);
        if (!terminals.isEmpty() && "ENABLED".equals(terminals.getFirst()) && !vehicles.isEmpty()) {
            long candidate = vehicles.getFirst();
            var vehicle = jdbc.queryForMap("SELECT fleet_id, status FROM vehicle WHERE id = ? FOR UPDATE", candidate);
            if ("ENABLED".equals(vehicle.get("status"))) {
                vehicleId = candidate;
                fleetId = ((Number) vehicle.get("fleet_id")).longValue();
            }
        }
        if (vehicleId == null) {
            recordAnomaly(terminalId, null, null, report.time(), receivedAt, "UNASSIGNABLE", body);
            return;
        }
        // 保留期外按 11 的保留策略留拒绝摘要，同时按 08 的质量语义记受限异常；不落轨迹、不推进快照。
        if (!retention.isWithinTrackWindow(report.time())) {
            recordRetentionRejection(terminalId, vehicleId, report, receivedAt);
            recordAnomaly(terminalId, vehicleId, fleetId, report.time(), receivedAt, "RETENTION_OUTSIDE", body);
            return;
        }
        long ageMillis = Duration.between(report.time(), receivedAt).toMillis();
        if (ageMillis < -FUTURE_TOLERANCE_MILLIS) {
            recordAnomaly(terminalId, vehicleId, fleetId, report.time(), receivedAt,
                    "FUTURE_BEYOND_TOLERANCE", body);
            return;
        }
        List<LocalDateTime> previous = jdbc.queryForList(
                "SELECT gps_time FROM vehicle_last_position WHERE vehicle_id = ?", LocalDateTime.class, vehicleId);
        // 实时候选：时差在 [0,60] 秒且新于已处理水位；容差内未来与过旧点只补历史。
        boolean realtime = ageMillis >= 0 && ageMillis <= REALTIME_MAX_AGE_MILLIS
                && (previous.isEmpty() || report.time().isAfter(previous.getFirst()));
        byte[] canonical = report.encode();
        String payloadHash = hash(canonical);
        try {
            jdbc.update("""
                    INSERT INTO track_point (terminal_id, vehicle_id, fleet_id_at_event, gps_time, received_at,
                        lng, lat, speed, direction, alarm_flag, status_flag, locate_state, report_type, payload_hash, raw_body)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, terminalId, vehicleId, fleetId, report.time(), receivedAt,
                    report.fixValid() ? report.longitude() : null,
                    report.fixValid() ? report.latitude() : null,
                    report.speed() / 10.0, report.direction(), report.alarmFlag(), report.status(),
                    report.fixValid() ? "VALID" : "INVALID", realtime ? "REALTIME" : "HISTORY",
                    payloadHash, canonical);
        } catch (DuplicateKeyException duplicate) {
            // 唯一键 (terminal_id, gps_time) 是幂等权威：只在此处理预期重传/冲突，其余 SQL 错误照旧回滚。
            handleExistingPosition(terminalId, vehicleId, fleetId, report, receivedAt, payloadHash, body);
            return;
        }
        if (realtime && report.fixValid()) {
            applyRealtimeState(terminalId, vehicleId, fleetId, report, receivedAt);
        }
        touchTerminal(terminalId, receivedAt);
    }

    private void recordRetentionRejection(long terminalId, long vehicleId, PositionReport report,
                                          LocalDateTime receivedAt) {
        byte[] canonical = report.encode();
        jdbc.update("""
                INSERT INTO track_retention_rejection (terminal_id, vehicle_id, gps_time, received_at,
                    reason, payload_hash, body_length, created_at)
                VALUES (?, ?, ?, ?, 'OUTSIDE_RETENTION', ?, ?, ?)
                """, terminalId, vehicleId, report.time(), receivedAt, hash(canonical), canonical.length, receivedAt);
    }

    private void applyRealtimeState(long terminalId, long vehicleId, long fleetId, PositionReport report,
                                    LocalDateTime receivedAt) {
        Long lastVersion = jdbc.query("SELECT version FROM vehicle_last_position WHERE vehicle_id = ?",
                rs -> rs.next() ? rs.getLong(1) : 0L, vehicleId);
        long version = lastVersion + 1;
        jdbc.update("""
                INSERT INTO vehicle_last_position (vehicle_id, terminal_id, gps_time, received_at,
                    lng, lat, speed, direction, status_flag, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE terminal_id=VALUES(terminal_id), gps_time=VALUES(gps_time),
                    received_at=VALUES(received_at), lng=VALUES(lng), lat=VALUES(lat), speed=VALUES(speed),
                    direction=VALUES(direction), status_flag=VALUES(status_flag), version=VALUES(version)
                """, vehicleId, terminalId, report.time(), receivedAt, report.longitude(), report.latitude(),
                report.speed() / 10.0, report.direction(), report.status(), version);
        jdbc.update("""
                INSERT INTO vehicle_monitor_state (vehicle_id, gps_time, version) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE gps_time=VALUES(gps_time), version=VALUES(version)
                """, vehicleId, report.time(), version);
        for (PositionStateParticipant participant : participants) {
            participant.apply(vehicleId, terminalId, fleetId, version, report, receivedAt);
        }
        jdbc.update("""
                INSERT INTO notification_outbox (event_id,event_type,aggregate_id,version,status,retry_at,attempts,updated_at)
                VALUES (?, 'VEHICLE_POSITION', ?, ?, 'PENDING', ?, 0, ?)
                ON DUPLICATE KEY UPDATE version=VALUES(version), status='PENDING', retry_at=VALUES(retry_at),
                    attempts=0, updated_at=VALUES(updated_at)
                """, "position:" + vehicleId, vehicleId, version, receivedAt, receivedAt);
    }

    private void handleExistingPosition(long terminalId, long vehicleId, long fleetId, PositionReport report,
                                        LocalDateTime receivedAt, String payloadHash, byte[] body) {
        List<String> existing = jdbc.queryForList(
                "SELECT payload_hash FROM track_point WHERE terminal_id = ? AND gps_time = ? FOR UPDATE",
                String.class, terminalId, report.time());
        if (existing.size() == 1 && payloadHash.equals(existing.getFirst())) {
            touchTerminal(terminalId, receivedAt);
            return;
        }
        // 同秒不同内容：保留首条正常位置，后到内容仅记受限异常，不覆盖快照、不进实时判定。
        recordAnomaly(terminalId, vehicleId, fleetId, report.time(), receivedAt, "CONFLICTING_CONTENT",
                payloadHash, body);
    }

    private void recordAnomaly(long terminalId, Long vehicleId, Long fleetId, LocalDateTime gpsTime,
                               LocalDateTime receivedAt, String reason, byte[] body) {
        recordAnomaly(terminalId, vehicleId, fleetId, gpsTime, receivedAt, reason, hash(body), body);
    }

    private void recordAnomaly(long terminalId, Long vehicleId, Long fleetId, LocalDateTime gpsTime,
                               LocalDateTime receivedAt, String reason, String payloadHash, byte[] body) {
        jdbc.update("""
                INSERT INTO telemetry_anomaly (terminal_id, vehicle_id, fleet_id_at_event, gps_time, received_at,
                    reason, payload_hash, body_length, raw_body)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, terminalId, vehicleId, fleetId, gpsTime, receivedAt, reason, payloadHash, body.length,
                Arrays.copyOf(body, Math.min(body.length, 255)));
        touchTerminal(terminalId, receivedAt);
    }

    private void touchTerminal(long terminalId, LocalDateTime receivedAt) {
        jdbc.update("UPDATE terminal SET last_seen_at = ?, updated_at = ? WHERE id = ?",
                receivedAt, receivedAt, terminalId);
    }

    private String hash(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
