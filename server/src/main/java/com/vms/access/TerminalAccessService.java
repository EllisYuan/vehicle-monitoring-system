package com.vms.access;

import com.vms.audit.AuditService;
import com.vms.identity.security.PasswordHasher;
import io.netty.channel.Channel;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TerminalAccessService {

    public static final int REGISTER_SUCCESS = 0;
    public static final int REGISTER_REJECTED = 4;
    public static final int AUTHENTICATION_SUCCESS = 0;
    public static final int AUTHENTICATION_FAILED = 1;

    private final JdbcTemplate jdbc;
    private final PasswordHasher hasher;
    private final TerminalConnectionRegistry connections;
    private final AuditService audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TerminalAccessService(JdbcTemplate jdbc, PasswordHasher hasher,
                                 TerminalConnectionRegistry connections, AuditService audit,
                                 Clock clock) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.connections = connections;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public RegistrationResult register(String terminalNo) {
        List<TerminalRow> rows = jdbc.query("""
                SELECT t.id, t.status,
                       EXISTS (SELECT 1 FROM terminal_vehicle_binding b
                               JOIN vehicle v ON v.id = b.vehicle_id
                               WHERE b.current_terminal_id = t.id AND v.status = 'ENABLED') AS bound
                FROM terminal t WHERE t.terminal_no = ?
                """, (rs, row) -> new TerminalRow(rs.getLong("id"), rs.getString("status"),
                        rs.getBoolean("bound")), terminalNo);
        if (rows.isEmpty() || !"ENABLED".equals(rows.getFirst().status()) || !rows.getFirst().bound()) {
            record("REGISTER_REJECTED", terminalNo, AuditService.RESULT_FAILURE, "reason=not_admissible");
            return new RegistrationResult(REGISTER_REJECTED, null);
        }
        byte[] randomBytes = new byte[18];
        random.nextBytes(randomBytes);
        String authCode = HexFormat.of().formatHex(randomBytes);
        String hash = hasher.hash(authCode);
        jdbc.update("UPDATE terminal SET auth_secret_hash = ?, updated_at = ? WHERE id = ?",
                hash, now(), rows.getFirst().id());
        record("REGISTER_ACCEPTED", terminalNo, AuditService.RESULT_SUCCESS, "result=accepted");
        return new RegistrationResult(REGISTER_SUCCESS,
                new Registration(rows.getFirst().id(), terminalNo, authCode));
    }

    @Transactional
    public TerminalConnectionRegistry.Session authenticate(Registration registration,
                                                            String authCode, Channel channel) {
        if (registration == null || authCode == null) {
            return null;
        }
        String terminalNo = registration.terminalNo();
        String hash = authHash(registration.terminalId());
        if (hash == null || !hasher.matches(authCode, hash)) {
            record("AUTHENTICATION_REJECTED", terminalNo, AuditService.RESULT_FAILURE,
                    "reason=invalid_credentials");
            return null;
        }
        LocalDateTime now = now();
        int updated = jdbc.update("""
                UPDATE terminal SET ever_authenticated = TRUE, last_seen_at = ?, updated_at = ?
                WHERE id = ? AND status = 'ENABLED'
                  AND EXISTS (SELECT 1 FROM terminal_vehicle_binding b
                              JOIN vehicle v ON v.id = b.vehicle_id
                              WHERE b.current_terminal_id = terminal.id AND v.status = 'ENABLED')
                """, now, now, registration.terminalId());
        if (updated != 1) {
            record("AUTHENTICATION_REJECTED", terminalNo, AuditService.RESULT_FAILURE,
                    "reason=not_admissible");
            return null;
        }
        TerminalConnectionRegistry.Session session = connections.replace(
                terminalNo, registration.terminalId(), channel);
        record("AUTHENTICATION_ACCEPTED", terminalNo, AuditService.RESULT_SUCCESS,
                "epoch=" + session.epoch());
        return session;
    }

    @Transactional
    public boolean touch(TerminalConnectionRegistry.Session session) {
        if (!connections.isCurrent(session)) {
            if (session != null) {
                record("COMMUNICATION_REJECTED", session.terminalNo(), AuditService.RESULT_FAILURE,
                        "reason=stale_session");
            }
            return false;
        }
        LocalDateTime now = now();
        int updated = jdbc.update("UPDATE terminal SET last_seen_at = ?, updated_at = ? WHERE id = ?"
                + " AND status = 'ENABLED'"
                + " AND EXISTS (SELECT 1 FROM terminal_vehicle_binding b JOIN vehicle v ON v.id = b.vehicle_id"
                + " WHERE b.current_terminal_id = terminal.id AND v.status = 'ENABLED')",
                now, now, session.terminalId());
        if (updated != 1) {
            record("COMMUNICATION_REJECTED", session.terminalNo(), AuditService.RESULT_FAILURE,
                    "reason=not_admissible");
            return false;
        }
        record("COMMUNICATION_ACCEPTED", session.terminalNo(), AuditService.RESULT_SUCCESS,
                "epoch=" + session.epoch());
        return true;
    }

    public void logout(TerminalConnectionRegistry.Session session) {
        if (connections.removeIfCurrent(session)) {
            record("LOGOUT", session.terminalNo(), AuditService.RESULT_SUCCESS,
                    "epoch=" + session.epoch());
        }
    }

    public void revoke(long terminalId) {
        connections.revoke(terminalId);
    }

    public boolean isCurrent(TerminalConnectionRegistry.Session session) {
        return connections.isCurrent(session);
    }

    public java.util.Optional<TerminalAccessPort.SessionView> current(String terminalNo) {
        return connections.current(terminalNo);
    }

    private String authHash(long terminalId) {
        List<String> hashes = jdbc.queryForList("SELECT auth_secret_hash FROM terminal WHERE id = ?",
                String.class, terminalId);
        return hashes.isEmpty() ? null : hashes.getFirst();
    }

    private void record(String action, String terminalNo, String result, String detail) {
        audit.record(AuditService.CATEGORY_TERMINAL, action, "terminal:" + terminalNo,
                result, null, null, null, detail);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    public record RegistrationResult(int result, Registration registration) { }
    public record Registration(long terminalId, String terminalNo, String authCode) { }
    private record TerminalRow(long id, String status, boolean bound) { }
}