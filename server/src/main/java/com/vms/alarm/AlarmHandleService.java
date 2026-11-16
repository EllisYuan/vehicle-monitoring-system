package com.vms.alarm;

import com.vms.audit.AuditService;
import com.vms.identity.access.FleetScope;
import com.vms.identity.access.FleetScopeResolver;
import com.vms.identity.account.UserAccount;
import com.vms.identity.security.ForbiddenException;
import com.vms.infrastructure.ClockConfig;
import com.vms.infrastructure.web.BadRequestException;
import com.vms.infrastructure.web.ConflictException;
import com.vms.infrastructure.web.NotFoundException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 13：人工处置状态机。一次处置在单个事务内完成条件更新、处置留痕、
 * outbox 事件与审计；条件状态与处置状态互不覆盖，终态不可重开。
 */
@Service
public class AlarmHandleService {

    static final String PROCESSING = "PROCESSING";
    static final String HANDLED = "HANDLED";
    static final String IGNORED = "IGNORED";
    private static final Map<String, String> REQUIRED_CURRENT = Map.of(
            PROCESSING, "PENDING", HANDLED, PROCESSING, IGNORED, PROCESSING);

    private final JdbcTemplate jdbc;
    private final FleetScopeResolver scopes;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AlarmHandleService(JdbcTemplate jdbc, FleetScopeResolver scopes, AuditService audit,
                              PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public HandleView handle(UserAccount actor, long alarmId, String targetStatus, String remark) {
        if (targetStatus == null || !REQUIRED_CURRENT.containsKey(targetStatus)) {
            throw new BadRequestException("ALARM_HANDLE_STATUS_INVALID", "不支持的处置目标状态");
        }
        String trimmed = remark == null ? null : remark.strip();
        if (trimmed != null && trimmed.length() > 500) {
            throw new BadRequestException("ALARM_HANDLE_REMARK_TOO_LONG", "处置备注不能超过 500 字");
        }
        List<AlarmState> rows = jdbc.query(
                "SELECT fleet_id_at_event,condition_state,handle_status FROM alarm WHERE id=?",
                (rs, index) -> new AlarmState(rs.getLong("fleet_id_at_event"),
                        rs.getString("condition_state"), rs.getString("handle_status")), alarmId);
        if (rows.isEmpty()) {
            throw new NotFoundException("ALARM_NOT_FOUND", "报警不存在");
        }
        AlarmState state = rows.getFirst();
        FleetScope scope = scopes.resolve(actor);
        if (!scope.contains(state.fleetId())) {
            throw new ForbiddenException();
        }
        String expected = REQUIRED_CURRENT.get(targetStatus);
        if (!expected.equals(state.handleStatus())) {
            throw illegal(state.handleStatus(), targetStatus);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return transactions.execute(status -> perform(actor, alarmId, targetStatus, expected, trimmed, now));
    }

    private HandleView perform(UserAccount actor, long alarmId, String targetStatus, String expected,
                               String remark, LocalDateTime now) {
        int updated = jdbc.update("""
                UPDATE alarm SET handle_status=?, updated_at=? WHERE id=? AND handle_status=?
                """, targetStatus, now, alarmId, expected);
        if (updated == 0) {
            throw new ConflictException("ALARM_HANDLE_ILLEGAL", "报警处置状态已变化，请刷新后按当前状态操作");
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO alarm_handle_log (alarm_id, change_type, from_status, to_status,
                        operator_id, remark, created_at)
                    VALUES (?, 'HANDLE', ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, alarmId);
            ps.setString(2, expected);
            ps.setString(3, targetStatus);
            ps.setLong(4, actor.getId());
            ps.setString(5, remark);
            ps.setObject(6, now);
            return ps;
        }, keys);
        long logId = keys.getKey().longValue();
        jdbc.update("""
                INSERT INTO notification_outbox (event_id, event_type, aggregate_id, version, status,
                    retry_at, attempts, updated_at)
                VALUES (?, 'ALARM_EVENT', ?, 1, 'PENDING', ?, 0, ?)
                """, "alarm:handle:" + alarmId + ":" + logId, alarmId, now, now);
        audit.record(AuditService.CATEGORY_ALARM, "HANDLE", "alarm:" + alarmId, AuditService.RESULT_SUCCESS,
                actor.getId(), actor.getUsername(), null,
                "fromStatus=" + expected + ", toStatus=" + targetStatus + ", logId=" + logId);
        String conditionState = jdbc.queryForObject(
                "SELECT condition_state FROM alarm WHERE id=?", String.class, alarmId);
        return new HandleView(alarmId, conditionState, targetStatus, logId,
                now.atZone(ClockConfig.BUSINESS_ZONE).toOffsetDateTime());
    }

    private ConflictException illegal(String current, String target) {
        return new ConflictException("ALARM_HANDLE_ILLEGAL",
                "不允许的处置状态迁移：" + current + " -> " + target);
    }

    public record HandleView(long alarmId, String conditionState, String handleStatus, long logId,
                             OffsetDateTime handledAt) { }

    private record AlarmState(long fleetId, String conditionState, String handleStatus) { }
}
