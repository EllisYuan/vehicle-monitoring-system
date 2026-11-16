package com.vms.reporting;

import com.vms.identity.auth.AuthService;
import com.vms.identity.security.Permission;
import com.vms.identity.security.RequiresPermission;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 20：三类同步 Excel 导出下载；失败由统一异常处理返回明确 JSON，不产生残缺文件。 */
@RestController
@RequestMapping("/api/v1/reports/exports")
@RequiresPermission(Permission.EXPORT_DATA)
public class ReportExportController {

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ReportExportService service;
    private final AuthService auth;
    private final Clock clock;

    public ReportExportController(ReportExportService service, AuthService auth, Clock clock) {
        this.service = service;
        this.auth = auth;
        this.clock = clock;
    }

    @GetMapping("/vehicles.xlsx")
    public ResponseEntity<byte[]> vehicles(@RequestParam(required = false) Long fleetId) {
        return file(service.exportVehicles(auth.currentUser(), fleetId), "vehicles");
    }

    @GetMapping("/alarms.xlsx")
    public ResponseEntity<byte[]> alarms(@RequestParam(required = false) Long fleetId) {
        return file(service.exportAlarms(auth.currentUser(), fleetId), "alarms");
    }

    @GetMapping("/handling-records.xlsx")
    public ResponseEntity<byte[]> handlingRecords(@RequestParam(required = false) Long fleetId) {
        return file(service.exportHandlingRecords(auth.currentUser(), fleetId), "handling-records");
    }

    private ResponseEntity<byte[]> file(byte[] content, String base) {
        String filename = base + "-" + LocalDateTime.now(clock).format(STAMP) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(content);
    }
}
