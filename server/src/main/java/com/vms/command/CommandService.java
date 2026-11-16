package com.vms.command;

import com.vms.access.TerminalAccessPort;
import com.vms.command.CommandStore.CommandRow;
import com.vms.audit.AuditService;
import com.vms.identity.access.FleetScope;
import com.vms.identity.access.FleetScopeResolver;
import com.vms.identity.account.UserAccount;
import com.vms.identity.security.ForbiddenException;
import com.vms.infrastructure.web.BadRequestException;
import com.vms.infrastructure.web.ConflictException;
import com.vms.infrastructure.web.NotFoundException;
import com.vms.protocol.BodyProperties;
import com.vms.protocol.Frame;
import com.vms.protocol.Gbk;
import com.vms.protocol.MessageHeader;
import com.vms.protocol.MessageType;
import com.vms.protocol.TemporaryTracking;
import com.vms.protocol.TextIssue;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文本与临时跟踪指令：先校验并由页面/API 提交，再按当前连接代次下发；
 * 提交结果与通信结果分列，离线拒绝不排队，超时只记录事实，手工重试生成新记录。
 */
@Service
public class CommandService {

    /** 100 个汉字按 GBK 计为 200 字节；同时远小于单包 1021 字节。 */
    public static final int MAX_TEXT_BYTES = 200;
    public static final int ACK_TIMEOUT_SECONDS = 30;

    private static final String TARGET_SQL = """
            SELECT t.id, t.terminal_no, t.status, v.id AS vehicle_id, v.fleet_id, v.status AS vehicle_status
            FROM terminal t
            LEFT JOIN terminal_vehicle_binding b ON b.current_terminal_id = t.id
            LEFT JOIN vehicle v ON v.id = b.vehicle_id
            WHERE t.id = ?
            """;

    private final CommandStore store;
    private final JdbcTemplate jdbc;
    private final FleetScopeResolver scopes;
    private final AuditService audit;
    private final TerminalAccessPort access;
    private final Clock clock;

    public CommandService(CommandStore store, JdbcTemplate jdbc, FleetScopeResolver scopes,
                          AuditService audit, TerminalAccessPort access, Clock clock) {
        this.store = store;
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.audit = audit;
        this.access = access;
        this.clock = clock;
    }

    public CommandView submitText(UserAccount actor, long terminalId, String text, Integer textType) {
        TextPayload payload = validateText(text, textType);
        return submit(actor, terminalId, new CommandContent("TEXT", MessageType.TEXT_ISSUE.id(),
                payload.text(), payload.textType(), null, null), null);
    }

    public CommandView submitTracking(UserAccount actor, long terminalId, Integer intervalSeconds,
                                      Long validSeconds) {
        TrackingPayload payload = validateTracking(intervalSeconds, validSeconds);
        return submit(actor, terminalId, new CommandContent("TRACKING",
                MessageType.TEMPORARY_TRACKING.id(), null, null,
                payload.intervalSeconds(), payload.validSeconds()), null);
    }

    public CommandView retry(UserAccount actor, long id) {
        expireOverdue();
        CommandRow original = getRow(actor, id);
        if (!CommandStore.STATUS_FAILED.equals(original.status())
                && !CommandStore.STATUS_TIMEOUT.equals(original.status())) {
            throw new ConflictException("COMMAND_NOT_RETRYABLE", "仅失败或超时的指令可以重试");
        }
        return submit(actor, original.terminalId(), new CommandContent(original.commandType(),
                original.messageId(), original.textContent(), original.textType(),
                original.intervalSeconds(), original.validSeconds()), original.id());
    }

    @Transactional
    public List<CommandView> list(UserAccount actor, Long terminalId) {
        expireOverdue();
        FleetScope scope = scopes.resolve(actor);
        if (terminalId != null && !terminalVisible(scope, terminalId)) {
            throw new ForbiddenException();
        }
        List<CommandView> views = new ArrayList<>();
        for (CommandRow row : store.list(scope, terminalId)) {
            views.add(view(row));
        }
        return views;
    }

    @Transactional
    public CommandView get(UserAccount actor, long id) {
        expireOverdue();
        return view(getRow(actor, id));
    }

    /**
     * 超过 30 秒未收到终端应答的在途指令转为 TIMEOUT；只扫描状态，不自动重发。
     */
    @Transactional
    public void expireOverdue() {
        LocalDateTime cutoff = now().minusSeconds(ACK_TIMEOUT_SECONDS);
        for (long id : store.expireOverdue(cutoff)) {
            audit.record(AuditService.CATEGORY_COMMAND, "TIMEOUT", "command:" + id,
                    AuditService.RESULT_FAILURE, null, null, null,
                    "timeoutSeconds=" + ACK_TIMEOUT_SECONDS);
        }
    }

    /** 车队长尝试下发时既拒绝操作，也把被拒绝的目标终端留入审计。 */
    public void auditDenied(UserAccount actor, long terminalId) {
        audit.record(AuditService.CATEGORY_COMMAND, "DENIED", "terminal:" + terminalId,
                AuditService.RESULT_FAILURE, actor.getId(), actor.getUsername(), null, null);
    }

    private CommandView submit(UserAccount actor, long terminalId, CommandContent content,
                               Long retryOfId) {
        TerminalTarget target = loadTarget(actor, terminalId);
        if (!target.admissible()) {
            rejectOffline(actor, terminalId);
        }
        Optional<TerminalAccessPort.SessionView> current = access.current(target.terminalNo())
                .filter(TerminalAccessPort.SessionView::writable);
        if (current.isEmpty()) {
            rejectOffline(actor, terminalId);
        }
        long epoch = current.get().epoch();
        CommandStore.Appended appended = store.append(new CommandStore.NewCommand(
                content.commandType(), content.messageId(), target.terminalId(), target.vehicleId(),
                epoch, content.textContent(), content.textType(), content.intervalSeconds(),
                content.validSeconds(), actor.getId(), retryOfId));
        store.markSent(appended.id(), now());

        byte[] body = content.commandType().equals("TEXT")
                ? new TextIssue(1, content.textType(), content.textContent()).encode()
                : new TemporaryTracking(content.intervalSeconds(), content.validSeconds()).encode();
        Frame frame = new Frame(new MessageHeader(content.messageId(),
                BodyProperties.plain(body.length), 1, target.terminalNo(), appended.serialNo()), body);
        TerminalAccessPort.SendResult sent = access.send(target.terminalNo(), epoch, frame);
        if (sent != TerminalAccessPort.SendResult.SENT) {
            store.markFailedIfSent(appended.id(), sent.name());
        }
        return view(store.find(appended.id()).orElseThrow());
    }

    private void rejectOffline(UserAccount actor, long terminalId) {
        audit.record(AuditService.CATEGORY_COMMAND, "OFFLINE_REJECTED", "terminal:" + terminalId,
                AuditService.RESULT_FAILURE, actor.getId(), actor.getUsername(), null, null);
        throw new ConflictException("COMMAND_TERMINAL_OFFLINE", "终端当前不在线，指令未提交");
    }

    private TerminalTarget loadTarget(UserAccount actor, long terminalId) {
        List<TerminalTarget> rows = jdbc.query(TARGET_SQL, (rs, row) -> new TerminalTarget(
                rs.getLong("id"), rs.getString("terminal_no"), rs.getString("status"),
                nullableLong(rs, "vehicle_id"), nullableLong(rs, "fleet_id"),
                rs.getString("vehicle_status")), terminalId);
        if (rows.isEmpty()) {
            throw new NotFoundException("TERMINAL_NOT_FOUND", "终端不存在");
        }
        TerminalTarget target = rows.getFirst();
        if (!scopes.resolve(actor).contains(target.fleetId())) {
            throw new ForbiddenException();
        }
        return target;
    }

    private boolean terminalVisible(FleetScope scope, long terminalId) {
        List<Long> fleets = jdbc.queryForList("""
                SELECT v.fleet_id FROM terminal t
                LEFT JOIN terminal_vehicle_binding b ON b.current_terminal_id = t.id
                LEFT JOIN vehicle v ON v.id = b.vehicle_id
                WHERE t.id = ?
                """, Long.class, terminalId);
        return !fleets.isEmpty() && scope.contains(fleets.getFirst());
    }

    private CommandRow getRow(UserAccount actor, long id) {
        FleetScope scope = scopes.resolve(actor);
        Optional<CommandRow> row = store.findFleetScoped(id, scope);
        if (row.isEmpty()) {
            if (!scope.all()) {
                throw new ForbiddenException();
            }
            throw new NotFoundException("COMMAND_NOT_FOUND", "指令不存在");
        }
        return row.get();
    }

    private CommandView view(CommandRow row) {
        Integer textBytes = row.textContent() == null ? null : Gbk.encode(row.textContent()).length;
        return new CommandView(row.id(), row.commandType(), row.messageId(), row.terminalId(),
                row.vehicleId(), row.serialNo(), row.connectionEpoch(), row.retryOfId(),
                row.textContent(), row.textType(), textBytes, row.intervalSeconds(), row.validSeconds(),
                new SubmissionView(CommandStore.STATUS_SUBMITTED, row.operatorId(), row.operatorName(),
                        row.submittedAt()),
                new CommunicationView(row.status(), row.sendTime(), row.replyTime(), row.replyResult(),
                        row.replyMessageId(), row.failureReason(), timeoutAt(row)));
    }

    /** 超时时刻按发送时刻加默认 30 秒推导：超时状态只说明期限内未应答，不改写发送与应答时间。 */
    private LocalDateTime timeoutAt(CommandRow row) {
        if (!CommandStore.STATUS_TIMEOUT.equals(row.status()) || row.sendTime() == null) {
            return null;
        }
        return row.sendTime().plusSeconds(ACK_TIMEOUT_SECONDS);
    }

    private TextPayload validateText(String text, Integer textType) {
        if (text == null || text.isBlank()) {
            throw new BadRequestException("COMMAND_TEXT_INVALID", "文本内容不能为空");
        }
        byte[] encoded;
        try {
            encoded = Gbk.encode(text);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("COMMAND_TEXT_INVALID", "文本必须是可编码的 GBK 内容");
        }
        if (encoded.length > MAX_TEXT_BYTES) {
            throw new BadRequestException("COMMAND_TEXT_TOO_LONG",
                    "文本不得超过 100 个汉字（GBK " + MAX_TEXT_BYTES + " 字节）");
        }
        int type = textType == null ? TextIssue.TYPE_NOTICE : textType;
        if (type != TextIssue.TYPE_NOTICE && type != TextIssue.TYPE_SERVICE) {
            throw new BadRequestException("COMMAND_TEXT_INVALID", "文本类型只支持 1 通知或 2 服务");
        }
        return new TextPayload(text, type);
    }

    private TrackingPayload validateTracking(Integer intervalSeconds, Long validSeconds) {
        if (intervalSeconds == null || intervalSeconds < 1 || intervalSeconds > 60
                || validSeconds == null || validSeconds < 0 || validSeconds > 3600) {
            throw new BadRequestException("COMMAND_TRACKING_INVALID",
                    "跟踪间隔必须为 1-60 秒，有效期必须为 0-3600 秒");
        }
        return new TrackingPayload(intervalSeconds, validSeconds);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    private record TextPayload(String text, int textType) { }

    private record TrackingPayload(int intervalSeconds, long validSeconds) { }

    private record CommandContent(String commandType, int messageId, String textContent,
                                  Integer textType, Integer intervalSeconds, Long validSeconds) { }

    private record TerminalTarget(long terminalId, String terminalNo, String terminalStatus,
                                  Long vehicleId, Long fleetId, String vehicleStatus) {
        boolean admissible() {
            return "ENABLED".equals(terminalStatus) && "ENABLED".equals(vehicleStatus)
                    && vehicleId != null;
        }
    }

    public record CommandView(long id, String commandType, int messageId, long terminalId,
                              Long vehicleId, int serialNo, long connectionEpoch, Long retryOfId,
                              String textContent, Integer textType, Integer textBytes,
                              Integer intervalSeconds, Long validSeconds, SubmissionView submission,
                              CommunicationView communication) { }

    public record SubmissionView(String status, long operatorId, String operatorName,
                                 LocalDateTime submittedAt) { }

    public record CommunicationView(String status, LocalDateTime sendTime, LocalDateTime replyTime,
                                    Integer replyResult, Integer replyMessageId,
                                    String failureReason, LocalDateTime timeoutAt) { }
}
