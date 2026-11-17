package com.vms.infrastructure.monitor;

import com.vms.audit.AuditService;
import com.vms.identity.account.UserAccount;
import com.vms.infrastructure.web.ConflictException;
import com.vms.infrastructure.web.NotFoundException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 18 AC4：DEAD 通知的可审计重投。重投只把同一 event_id 从 DEAD 条件改回 PENDING 并清零 attempts，
 * 不新增业务事件、不改写 event_type/aggregate_id/version，也不触碰原业务事实；
 * 读取后若新版本已覆盖该事件，条件更新不命中，返回冲突而不覆盖新状态。
 */
@Service
public class OutboxRedeliveryService {

    private static final String DEAD = "DEAD";
    private static final String PENDING = "PENDING";

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OutboxRedeliveryService(JdbcTemplate jdbc, AuditService audit,
                                   PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public RedeliveryView redeliver(UserAccount actor, String eventId) {
        List<EventRow> rows = jdbc.query("""
                SELECT event_type, aggregate_id, version, status FROM notification_outbox WHERE event_id = ?
                """, (rs, row) -> new EventRow(rs.getString("event_type"), rs.getLong("aggregate_id"),
                rs.getLong("version"), rs.getString("status")), eventId);
        if (rows.isEmpty()) {
            auditFailure(actor, eventId, null, "reason=NOT_FOUND");
            throw new NotFoundException("OUTBOX_EVENT_NOT_FOUND", "通知事件不存在");
        }
        EventRow event = rows.getFirst();
        if (!DEAD.equals(event.status())) {
            auditFailure(actor, eventId, event, "reason=NOT_DEAD");
            throw new ConflictException("OUTBOX_NOT_DEAD", "只有 DEAD 通知可以重投");
        }
        RedeliveryView view = transactions.execute(status -> {
            LocalDateTime now = LocalDateTime.now(clock);
            int updated = jdbc.update("""
                    UPDATE notification_outbox SET status='PENDING', attempts=0, retry_at=?, updated_at=?
                    WHERE event_id=? AND version=? AND status='DEAD'
                    """, now, now, eventId, event.version());
            if (updated == 0) {
                return null;
            }
            audit.record(AuditService.CATEGORY_OUTBOX, "REDELIVER", target(eventId),
                    AuditService.RESULT_SUCCESS, actor.getId(), actor.getUsername(), null,
                    "eventType=" + event.eventType() + ", fromStatus=DEAD, toStatus=PENDING");
            return new RedeliveryView(eventId, event.eventType(), event.aggregateId(), event.version(),
                    PENDING, 0);
        });
        if (view == null) {
            auditFailure(actor, eventId, event, "reason=STATE_CHANGED");
            throw new ConflictException("OUTBOX_NOT_DEAD", "通知状态已变化，请刷新后重试");
        }
        return view;
    }

    private void auditFailure(UserAccount actor, String eventId, EventRow event, String reason) {
        String detail = event == null
                ? reason
                : "eventType=" + event.eventType() + ", status=" + event.status() + ", " + reason;
        audit.record(AuditService.CATEGORY_OUTBOX, "REDELIVER", target(eventId),
                AuditService.RESULT_FAILURE, actor.getId(), actor.getUsername(), null, detail);
    }

    private String target(String eventId) {
        return "outbox:" + eventId;
    }

    private record EventRow(String eventType, long aggregateId, long version, String status) { }

    public record RedeliveryView(String eventId, String eventType, long aggregateId, long version,
                                 String status, int attempts) { }
}