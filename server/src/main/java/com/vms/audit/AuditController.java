package com.vms.audit;

import com.vms.identity.security.Permission;
import com.vms.identity.security.RequiresPermission;
import com.vms.infrastructure.web.BadRequestException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员只读审计查询；不提供删除、修改或补录入口。
 * 筛选条件在服务端解析与校验，分页大小与时间窗口均有上限。
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiresPermission(Permission.AUDIT_VIEW)
public class AuditController {

    private final AuditQueryService auditQueryService;

    public AuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping("/logs")
    public AuditQueryService.AuditPage logs(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return auditQueryService.search(actor, category, action, result,
                parseTime(from, "from"), parseTime(to, "to"), page, size);
    }

    @GetMapping("/operation-records")
    public AuditQueryService.OperationRecords operationRecords() {
        return auditQueryService.operationRecords();
    }

    private static LocalDateTime parseTime(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("INVALID_TIME_RANGE",
                    "时间参数 " + name + " 需为 ISO-8601 本地时间，例如 2026-11-16T00:00:00");
        }
    }
}
