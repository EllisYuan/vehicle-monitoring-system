package com.vms.telemetry;

import com.vms.access.TerminalConnectionRegistry;
import com.vms.protocol.PositionReport;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PositionIngestService {
    private final JdbcTemplate jdbc;
    private final TerminalConnectionRegistry connections;
    private final List<PositionStateParticipant> participants;
    public PositionIngestService(JdbcTemplate jdbc, TerminalConnectionRegistry connections,
                                 List<PositionStateParticipant> participants) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.participants = participants;
    }

    @Transactional
    public void accept(TerminalConnectionRegistry.Session session, PositionReport report, LocalDateTime receivedAt) {
        List<String> terminals = jdbc.queryForList("SELECT status FROM terminal WHERE id = ? FOR UPDATE",
                String.class, session.terminalId());
        if (terminals.isEmpty() || !"ENABLED".equals(terminals.getFirst()) || !connections.isCurrent(session)) {
            throw new IllegalStateException("Terminal session is no longer admissible");
        }
        List<Long> vehicles = jdbc.queryForList(
                "SELECT vehicle_id FROM terminal_vehicle_binding WHERE current_terminal_id = ?",
                Long.class, session.terminalId());
        if (vehicles.isEmpty()) throw new IllegalStateException("Terminal is not bound");
        long vehicleId = vehicles.getFirst();
        var vehicle = jdbc.queryForMap("SELECT fleet_id, status FROM vehicle WHERE id = ? FOR UPDATE", vehicleId);
        if (!"ENABLED".equals(vehicle.get("status"))) throw new IllegalStateException("Vehicle is disabled");
        // 本票只接收正常有效单点；质量、幂等与历史归属由后续 ticket 扩展。
        if (!report.fixValid()) throw new IllegalArgumentException("Invalid fixes are not accepted yet");
        long fleetId = ((Number) vehicle.get("fleet_id")).longValue();
        byte[] body = report.encode();
        jdbc.update("""
                INSERT INTO track_point (terminal_id, vehicle_id, fleet_id_at_event, gps_time, received_at,
                    lng, lat, speed, direction, alarm_flag, status_flag, locate_state, report_type, payload_hash, raw_body)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'VALID', 'REALTIME', ?, ?)
                """, session.terminalId(), vehicleId, fleetId, report.time(), receivedAt,
                report.longitude(), report.latitude(), report.speed() / 10.0, report.direction(), report.alarmFlag(),
                report.status(), hash(body), body);
        List<LocalDateTime> previous = jdbc.queryForList(
                "SELECT gps_time FROM vehicle_last_position WHERE vehicle_id = ?", LocalDateTime.class, vehicleId);
        if (previous.isEmpty() || report.time().isAfter(previous.getFirst())) {
            Long lastVersion = jdbc.query("SELECT version FROM vehicle_last_position WHERE vehicle_id = ?",
                    rs -> rs.next() ? rs.getLong(1) : 0L, vehicleId);
            long version = lastVersion + 1;
            jdbc.update("""
                    INSERT INTO vehicle_last_position (vehicle_id, terminal_id, gps_time, received_at,
                        lng, lat, speed, direction, status_flag, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE terminal_id=VALUES(terminal_id), gps_time=VALUES(gps_time),
                        received_at=VALUES(received_at), lng=VALUES(lng), lat=VALUES(lat), speed=VALUES(speed),
                        direction=VALUES(direction), status_flag=VALUES(status_flag), version=VALUES(version)
                    """, vehicleId, session.terminalId(), report.time(), receivedAt, report.longitude(), report.latitude(),
                    report.speed() / 10.0, report.direction(), report.status(), version);
            jdbc.update("""
                    INSERT INTO vehicle_monitor_state (vehicle_id, gps_time, version) VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE gps_time=VALUES(gps_time), version=VALUES(version)
                    """, vehicleId, report.time(), version);
            for (PositionStateParticipant participant : participants) {
                participant.apply(vehicleId, session.terminalId(), fleetId, version, report, receivedAt);
            }
            jdbc.update("""
                    INSERT INTO notification_outbox (event_id,event_type,aggregate_id,version,status,retry_at,attempts,updated_at)
                    VALUES (?, 'VEHICLE_POSITION', ?, ?, 'PENDING', ?, 0, ?)
                    ON DUPLICATE KEY UPDATE version=VALUES(version), status='PENDING', retry_at=VALUES(retry_at),
                        attempts=0, updated_at=VALUES(updated_at)
                    """, "position:" + vehicleId, vehicleId, version, receivedAt, receivedAt);
        }
        jdbc.update("UPDATE terminal SET last_seen_at = ?, updated_at = ? WHERE id = ?",
                receivedAt, receivedAt, session.terminalId());
    }

    private String hash(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
