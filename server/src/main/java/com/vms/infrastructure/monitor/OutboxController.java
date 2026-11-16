package com.vms.infrastructure.monitor;

import com.vms.identity.auth.AuthService;
import com.vms.identity.security.Permission;
import com.vms.identity.security.RequiresPermission;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 18 AC4：DEAD 通知的可见列表沿用 /api/v1/audit/operation-records（AUDIT_VIEW），
 * 重投为独立管理操作，仅 ADMIN 可发起并逐次写 OUTBOX/REDELIVER 审计。
 */
@RestController
@RequestMapping("/api/v1/outbox")
@RequiresPermission(Permission.OUTBOX_REDELIVER)
public class OutboxController {

    private final OutboxRedeliveryService service;
    private final AuthService authService;

    public OutboxController(OutboxRedeliveryService service, AuthService authService) {
        this.service = service;
        this.authService = authService;
    }

    @PostMapping("/{eventId}/redeliver")
    public OutboxRedeliveryService.RedeliveryView redeliver(@PathVariable String eventId) {
        return service.redeliver(authService.currentUser(), eventId);
    }
}