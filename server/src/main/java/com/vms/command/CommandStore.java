package com.vms.command;

import com.vms.identity.access.FleetScope;
import com.vms.identity.access.FleetScopeSql;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 指令记录的唯一持久化入口：串行号分配与状态推进都在这里完成，
 * 业务层只按记录事实编排，不直接拼 SQL。
 */
@Component
public class CommandStore {

    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_ACK_SUCCESS = "ACK_SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    private static final String SELECT = """
            SELECT c.id, c.command_type, c.message_id, c.terminal_id, c.vehicle_id, c.serial_no,
                   c.connection_epoch, c.status, c.failure_reason, c.text_content, c.text_type,
                   c.interval_seconds, c.valid_seconds, c.reply_message_id, c.reply_result, c.reply_time,
                   c.operator_id, u.username AS operator_name, c.submitted_at, c.send_time, c.retry_of_id
            FROM command_log c
            LEFT JOIN vehicle v ON v.id = c.vehicle_id
            LEFT JOIN sys_user u ON u.id = c.operator_id
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CommandStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 分配串行号并写入 SUBMITTED 记录；返回时事务已提交，终端应答可以立即匹配到该行。
     */
    @Transactional
    public Appended append(NewCommand command) {
        int serial = nextSerial(command.terminalId());
        LocalDateTime now = now();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO command_log
                        (command_type, message_id, terminal_id, vehicle_id, serial_no, connection_epoch,
                         status, text_content, text_type, interval_seconds, valid_seconds, operator_id,
                         submitted_at, retry_of_id)
                    VALUES (?, ?, ?, ?, ?, ?, 'SUBMITTED', ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, command.commandType());
            statement.setInt(2, command.messageId());
            statement.setLong(3, command.terminalId());
            if (command.vehicleId() == null) {
                statement.setNull(4, Types.BIGINT);
            } else {
                statement.setLong(4, command.vehicleId());
            }
            statement.setInt(5, serial);
            statement.setLong(6, command.connectionEpoch());
            statement.setString(7, command.textContent());
            if (command.textType() == null) {
                statement.setNull(8, Types.TINYINT);
            } else {
                statement.setInt(8, command.textType());
            }
            if (command.intervalSeconds() == null) {
                statement.setNull(9, Types.INTEGER);
            } else {
                statement.setInt(9, command.intervalSeconds());
            }
            if (command.validSeconds() == null) {
                statement.setNull(10, Types.BIGINT);
            } else {
                statement.setLong(10, command.validSeconds());
            }
            statement.setLong(11, command.operatorId());
            statement.setObject(12, now);
            if (command.retryOfId() == null) {
                statement.setNull(13, Types.BIGINT);
            } else {
                statement.setLong(13, command.retryOfId());
            }
            return statement;
        }, key);
        return new Appended(key.getKey().longValue(), serial);
    }

    @Transactional
    public int markSent(long id, LocalDateTime at) {
        return jdbc.update("""
                UPDATE command_log SET status = 'SENT', send_time = ?
                WHERE id = ? AND status = 'SUBMITTED'
                """, at, id);
    }

    @Transactional
    public int markFailedIfSent(long id, String failureReason) {
        return jdbc.update("""
                UPDATE command_log SET status = 'FAILED', failure_reason = ?
                WHERE id = ? AND status = 'SENT'
                """, failureReason, id);
    }

    /**
     * 终端应答只推进仍在途且代次匹配的记录；条件更新保证迟到或旧代次应答不改写历史。
     */
    @Transactional
    public int complete(long id, long epoch, int replyMessageId, int replyResult, LocalDateTime replyTime) {
        boolean success = replyResult == 0;
        return jdbc.update("""
                UPDATE command_log
                SET status = ?, failure_reason = ?, reply_message_id = ?, reply_result = ?, reply_time = ?,
                    send_time = COALESCE(send_time, ?)
                WHERE id = ? AND connection_epoch = ? AND status IN ('SENT', 'SUBMITTED')
                """, success ? STATUS_ACK_SUCCESS : STATUS_FAILED,
                success ? null : "TERMINAL_REJECTED", replyMessageId, replyResult, replyTime, replyTime,
                id, epoch);
    }

    /**
     * 合法注销只收尾同一连接代次下在途的指令；旧代次遗留记录保持原状以便追溯。
     */
    @Transactional
    public List<Long> failInFlightForLogout(long terminalId, long epoch) {
        List<Long> candidates = jdbc.queryForList("""
                SELECT id FROM command_log
                WHERE terminal_id = ? AND connection_epoch = ? AND status = 'SENT'
                ORDER BY id FOR UPDATE
                """, Long.class, terminalId, epoch);
        List<Long> failed = new ArrayList<>();
        for (Long id : candidates) {
            if (jdbc.update("""
                    UPDATE command_log SET status = 'FAILED', failure_reason = 'LOGOUT'
                    WHERE id = ? AND status = 'SENT'
                    """, id) == 1) {
                failed.add(id);
            }
        }
        return failed;
    }

    /**
     * 超过时限仍未应答的指令转为 TIMEOUT；条件更新保证并发扫描只生效一次。
     */
    @Transactional
    public List<Long> expireOverdue(LocalDateTime cutoff) {
        List<Long> candidates = jdbc.queryForList("""
                SELECT id FROM command_log
                WHERE status = 'SENT' AND send_time IS NOT NULL AND send_time <= ?
                ORDER BY id FOR UPDATE
                """, Long.class, cutoff);
        List<Long> expired = new ArrayList<>();
        for (Long id : candidates) {
            if (jdbc.update("""
                    UPDATE command_log SET status = 'TIMEOUT', failure_reason = NULL
                    WHERE id = ? AND status = 'SENT'
                    """, id) == 1) {
                expired.add(id);
            }
        }
        return expired;
    }

    public Optional<CommandRow> find(long id) {
        List<CommandRow> rows = jdbc.query(SELECT + " WHERE c.id = ?", this::map, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Optional<CommandRow> findFleetScoped(long id, FleetScope scope) {
        List<Object> args = new ArrayList<>();
        String where = FleetScopeSql.where("v.fleet_id", scope, args);
        args.add(id);
        List<CommandRow> rows = jdbc.query(SELECT + " WHERE " + where + " AND c.id = ?",
                this::map, args.toArray());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<CommandRow> list(FleetScope scope, Long terminalId) {
        List<Object> args = new ArrayList<>();
        String where = FleetScopeSql.where("v.fleet_id", scope, args);
        if (terminalId != null) {
            where += " AND c.terminal_id = ?";
            args.add(terminalId);
        }
        return jdbc.query(SELECT + " WHERE " + where + " ORDER BY c.id DESC",
                this::map, args.toArray());
    }

    public Optional<CommandRow> findBySerial(long terminalId, int serialNo, int messageId) {
        List<CommandRow> rows = jdbc.query(SELECT
                + " WHERE c.terminal_id = ? AND c.serial_no = ? AND c.message_id = ?"
                + " ORDER BY c.id DESC LIMIT 1",
                this::map, terminalId, serialNo, messageId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private int nextSerial(long terminalId) {
        jdbc.update("""
                INSERT INTO terminal_command_sequence (terminal_id, last_serial) VALUES (?, 1)
                ON DUPLICATE KEY UPDATE last_serial = IF(last_serial >= 65535, 1, last_serial + 1)
                """, terminalId);
        Integer serial = jdbc.queryForObject(
                "SELECT last_serial FROM terminal_command_sequence WHERE terminal_id = ?",
                Integer.class, terminalId);
        return serial == null ? 1 : serial;
    }

    private CommandRow map(ResultSet rs, int row) throws SQLException {
        return new CommandRow(rs.getLong("id"), rs.getString("command_type"), rs.getInt("message_id"),
                rs.getLong("terminal_id"), nullableLong(rs, "vehicle_id"), rs.getInt("serial_no"),
                rs.getLong("connection_epoch"), rs.getString("status"), rs.getString("failure_reason"),
                rs.getString("text_content"), nullableInteger(rs, "text_type"),
                nullableInteger(rs, "interval_seconds"), nullableLong(rs, "valid_seconds"),
                nullableInteger(rs, "reply_message_id"), nullableInteger(rs, "reply_result"),
                rs.getObject("reply_time", LocalDateTime.class), rs.getLong("operator_id"),
                rs.getString("operator_name"), rs.getObject("submitted_at", LocalDateTime.class),
                rs.getObject("send_time", LocalDateTime.class), nullableLong(rs, "retry_of_id"));
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    public record NewCommand(String commandType, int messageId, long terminalId, Long vehicleId,
                             long connectionEpoch, String textContent, Integer textType,
                             Integer intervalSeconds, Long validSeconds, long operatorId,
                             Long retryOfId) { }

    public record Appended(long id, int serialNo) { }

    public record CommandRow(long id, String commandType, int messageId, long terminalId,
                             Long vehicleId, int serialNo, long connectionEpoch, String status,
                             String failureReason, String textContent, Integer textType,
                             Integer intervalSeconds, Long validSeconds, Integer replyMessageId,
                             Integer replyResult, LocalDateTime replyTime, long operatorId,
                             String operatorName, LocalDateTime submittedAt, LocalDateTime sendTime,
                             Long retryOfId) { }
}
