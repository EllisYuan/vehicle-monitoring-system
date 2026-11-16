package com.vms.reporting;

import com.vms.alarm.AlarmQueryService;
import com.vms.audit.AuditService;
import com.vms.fleet.VehicleService;
import com.vms.identity.account.UserAccount;
import com.vms.identity.security.ForbiddenException;
import com.vms.infrastructure.ClockConfig;
import com.vms.infrastructure.web.BadRequestException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** 20：三类同步 Excel 导出；导出正文不入审计，成功或失败均按同一发起记录结果。 */
@Service
public class ReportExportService {

    private static final long MAX_ROWS = 10_000;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VehicleService vehicles;
    private final AlarmQueryService alarms;
    private final AuditService audit;

    public ReportExportService(VehicleService vehicles, AlarmQueryService alarms, AuditService audit) {
        this.vehicles = vehicles;
        this.alarms = alarms;
        this.audit = audit;
    }

    public byte[] exportVehicles(UserAccount user, Long fleetId) {
        String filters = "fleetId=" + (fleetId == null ? "ALL" : fleetId);
        long total;
        try {
            total = vehicles.count(user, fleetId);
        } catch (RuntimeException e) {
            failed(user, "VEHICLES", filters, 0, reason(e));
            throw e;
        }
        ensureWithinLimit(user, "VEHICLES", filters, total);
        List<VehicleService.VehicleView> rows;
        try {
            rows = vehicles.list(user, fleetId);
        } catch (RuntimeException e) {
            failed(user, "VEHICLES", filters, 0, reason(e));
            throw e;
        }
        ensureWithinLimit(user, "VEHICLES", filters, rows.size());
        List<List<String>> values = new ArrayList<>(rows.size());
        for (VehicleService.VehicleView row : rows) {
            values.add(List.of(row.plateNo(), row.fleetName(), statusLabel(row.status()),
                    row.terminalNo() == null ? "" : row.terminalNo()));
        }
        byte[] content;
        try {
            content = ExcelWorkbook.textWorkbook("车辆", List.of("车牌", "车队", "状态", "终端编号"), values);
        } catch (RuntimeException e) {
            failed(user, "VEHICLES", filters, rows.size(), "WRITE_FAILED");
            throw e;
        }
        audit.record(AuditService.CATEGORY_EXPORT, "EXPORT", "export:VEHICLES",
                AuditService.RESULT_SUCCESS, user.getId(), user.getUsername(), null,
                "type=VEHICLES, " + filters + ", rows=" + rows.size());
        return content;
    }

    public byte[] exportAlarms(UserAccount user, Long fleetId) {
        String filters = "fleetId=" + (fleetId == null ? "ALL" : fleetId);
        long total;
        try {
            total = alarms.count(user, fleetId);
        } catch (RuntimeException e) {
            failed(user, "ALARMS", filters, 0, reason(e));
            throw e;
        }
        ensureWithinLimit(user, "ALARMS", filters, total);
        List<AlarmQueryService.AlarmRow> rows;
        try {
            rows = alarms.list(user, fleetId);
        } catch (RuntimeException e) {
            failed(user, "ALARMS", filters, 0, reason(e));
            throw e;
        }
        ensureWithinLimit(user, "ALARMS", filters, rows.size());
        List<List<String>> values = new ArrayList<>(rows.size());
        for (AlarmQueryService.AlarmRow row : rows) {
            values.add(List.of(row.plateNo(), row.fleetName(), row.source(), row.alarmType(),
                    row.conditionState(), row.handleStatus(), time(row.startTime()), time(row.lastTime()),
                    time(row.recoveredAt())));
        }
        byte[] content;
        try {
            content = ExcelWorkbook.textWorkbook("报警", List.of("车牌", "车队", "来源", "报警类型",
                    "条件状态", "处置状态", "开始时间", "最后时间", "恢复时间"), values);
        } catch (RuntimeException e) {
            failed(user, "ALARMS", filters, rows.size(), "WRITE_FAILED");
            throw e;
        }
        audit.record(AuditService.CATEGORY_EXPORT, "EXPORT", "export:ALARMS",
                AuditService.RESULT_SUCCESS, user.getId(), user.getUsername(), null,
                "type=ALARMS, " + filters + ", rows=" + rows.size());
        return content;
    }

    public byte[] exportHandlingRecords(UserAccount user, Long fleetId) {
        String filters = "fleetId=" + (fleetId == null ? "ALL" : fleetId);
        long total;
        try {
            total = alarms.countHandlingRecords(user, fleetId);
        } catch (RuntimeException e) {
            failed(user, "HANDLING", filters, 0, reason(e));
            throw e;
        }
        ensureWithinLimit(user, "HANDLING", filters, total);
        List<AlarmQueryService.HandlingRow> rows;
        try {
            rows = alarms.handlingRecords(user, fleetId);
        } catch (RuntimeException e) {
            failed(user, "HANDLING", filters, 0, reason(e));
            throw e;
        }
        ensureWithinLimit(user, "HANDLING", filters, rows.size());
        List<List<String>> values = new ArrayList<>(rows.size());
        for (AlarmQueryService.HandlingRow row : rows) {
            values.add(List.of(row.plateNo(), row.fleetName(), row.changeType(), row.fromStatus(),
                    row.toStatus(), row.operatorName() == null ? "" : row.operatorName(),
                    row.remark() == null ? "" : row.remark(), time(row.createdAt())));
        }
        byte[] content;
        try {
            content = ExcelWorkbook.textWorkbook("处置记录", List.of("车牌", "车队", "变更类型", "原状态",
                    "新状态", "操作人", "备注", "时间"), values);
        } catch (RuntimeException e) {
            failed(user, "HANDLING", filters, rows.size(), "WRITE_FAILED");
            throw e;
        }
        audit.record(AuditService.CATEGORY_EXPORT, "EXPORT", "export:HANDLING",
                AuditService.RESULT_SUCCESS, user.getId(), user.getUsername(), null,
                "type=HANDLING, " + filters + ", rows=" + rows.size());
        return content;
    }

    private void ensureWithinLimit(UserAccount user, String type, String filters, long rows) {
        if (rows > MAX_ROWS) {
            failed(user, type, filters, rows, "ROW_LIMIT");
            throw new BadRequestException("EXPORT_ROW_LIMIT_EXCEEDED",
                    "导出数据超过 " + MAX_ROWS + " 行，请缩小筛选范围后重试");
        }
    }

    private static String time(OffsetDateTime value) {
        return value == null ? ""
                : value.atZoneSameInstant(ClockConfig.BUSINESS_ZONE).toLocalDateTime().format(STAMP);
    }

    private static String statusLabel(String status) {
        return "ENABLED".equals(status) ? "启用" : "DISABLED".equals(status) ? "停用" : status;
    }

    private String reason(RuntimeException e) {
        return e instanceof ForbiddenException ? "FORBIDDEN" : "QUERY_FAILED";
    }

    private void failed(UserAccount user, String type, String filters, long rows, String reason) {
        audit.record(AuditService.CATEGORY_EXPORT, "EXPORT", "export:" + type, AuditService.RESULT_FAILURE,
                user.getId(), user.getUsername(), null,
                "type=" + type + ", " + filters + ", rows=" + rows + ", reason=" + reason);
    }
}
