package com.vms.command;

import com.vms.access.CommandLifecycleListener;
import com.vms.audit.AuditService;
import com.vms.protocol.MessageType;
import com.vms.protocol.TerminalGeneralResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 终端通用应答与合法注销的生命周期收尾。
 * 只在终端、串行号、应答消息 ID 与连接代次同时匹配时推进状态；
 * 超时后的迟到应答与旧代次应答只留审计事实，不改写历史。
 */
@Service
public class CommandLifecycleService implements CommandLifecycleListener {

    private static final int TERMINAL_GENERAL_RESPONSE_ID = MessageType.TERMINAL_GENERAL_RESPONSE.id();

    private final CommandStore store;
    private final AuditService audit;
    private final Clock clock;

    public CommandLifecycleService(CommandStore store, AuditService audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void onTerminalReply(long terminalId, String terminalNo, long epoch,
                                TerminalGeneralResponse response) {
        Optional<CommandStore.CommandRow> found = store.findBySerial(terminalId,
                response.responseSerial(), response.responseId());
        if (found.isEmpty()) {
            return;
        }
        CommandStore.CommandRow row = found.get();
        if (!CommandStore.STATUS_SENT.equals(row.status())) {
            audit.record(AuditService.CATEGORY_COMMAND, "LATE_REPLY", "command:" + row.id(),
                    AuditService.RESULT_FAILURE, null, null, null,
                    "status=" + row.status() + ",epoch=" + epoch);
            return;
        }
        if (row.connectionEpoch() != epoch) {
            audit.record(AuditService.CATEGORY_COMMAND, "STALE_EPOCH_REPLY", "command:" + row.id(),
                    AuditService.RESULT_FAILURE, null, null, null,
                    "expectedEpoch=" + row.connectionEpoch() + ",actualEpoch=" + epoch);
            return;
        }
        int updated = store.complete(row.id(), epoch, TERMINAL_GENERAL_RESPONSE_ID,
                response.result(), now());
        if (updated == 0) {
            audit.record(AuditService.CATEGORY_COMMAND, "LATE_REPLY", "command:" + row.id(),
                    AuditService.RESULT_FAILURE, null, null, null, "reason=status_changed");
            return;
        }
        boolean success = response.result() == 0;
        audit.record(AuditService.CATEGORY_COMMAND, success ? "ACK_SUCCESS" : "TERMINAL_REJECTED",
                "command:" + row.id(), success ? AuditService.RESULT_SUCCESS : AuditService.RESULT_FAILURE,
                null, null, null, "result=" + response.result());
    }

    @Override
    @Transactional
    public void onLawfulLogout(long terminalId, String terminalNo, long epoch) {
        List<Long> failed = store.failInFlightForLogout(terminalId, epoch);
        if (!failed.isEmpty()) {
            audit.record(AuditService.CATEGORY_COMMAND, "LOGOUT_FAILED", "terminal:" + terminalId,
                    AuditService.RESULT_FAILURE, null, null, null,
                    "count=" + failed.size() + ",epoch=" + epoch);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }
}
