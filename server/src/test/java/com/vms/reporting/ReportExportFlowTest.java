package com.vms.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.vms.support.IntegrationContainers;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 20：三类 Excel 导出的真实 HTTP、文件内容与数据库一致性验证。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "vms.bootstrap-admin.username=export-admin",
        "vms.bootstrap-admin.password=Export-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0"
})
@Import(IntegrationContainers.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReportExportFlowTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @LocalServerPort int httpPort;

    @Test
    @Order(1)
    void vehicleExportProducesWorkbookMatchingThePageRows() throws Exception {
        String token = login("export-admin", "Export-Pass-2026");
        String suffix = suffix();
        long fleet = create(token, "/fleets", Map.of("fleetCode", "EXP" + suffix, "fleetName", "导出车队" + suffix));
        String plateB = "导出B" + suffix;
        String plateA = "导出A" + suffix;
        create(token, "/vehicles", Map.of("plateNo", plateB, "fleetId", fleet));
        create(token, "/vehicles", Map.of("plateNo", plateA, "fleetId", fleet));

        BinaryCall file = download(token, "/reports/exports/vehicles.xlsx?fleetId=" + fleet);
        assertThat(file.status()).as("下载导出文件").isEqualTo(200);
        assertThat(file.header("content-type")).isEqualTo(XLSX);
        assertThat(file.header("content-disposition")).startsWith("attachment;").contains(".xlsx");

        List<List<String>> rows = read(file.body());
        assertThat(rows.getFirst()).containsExactly("车牌", "车队", "状态", "终端编号");
        assertThat(column(rows, 0)).containsExactly(plateA, plateB);

        JsonNode page = get(token, "/vehicles?fleetId=" + fleet);
        List<String> pagePlates = new ArrayList<>();
        page.forEach(row -> pagePlates.add(row.path("plateNo").asText()));
        assertThat(column(rows, 0)).isEqualTo(pagePlates);
    }

    @Test
    @Order(2)
    void alarmExportMatchesThePageRowsAndWhitelist() throws Exception {
        String token = login("export-admin", "Export-Pass-2026");
        String suffix = suffix();
        long fleet = create(token, "/fleets", Map.of("fleetCode", "ALM" + suffix, "fleetName", "报警导出车队" + suffix));
        String plateA = "报出A" + suffix;
        String plateB = "报出B" + suffix;
        long vehicleA = create(token, "/vehicles", Map.of("plateNo", plateA, "fleetId", fleet));
        long vehicleB = create(token, "/vehicles", Map.of("plateNo", plateB, "fleetId", fleet));
        long terminalA = create(token, "/terminals", Map.of("terminalNo", terminalNo()));
        long terminalB = create(token, "/terminals", Map.of("terminalNo", terminalNo()));
        assertThat(status("PUT", "/vehicles/" + vehicleA + "/terminal", token,
                Map.of("terminalId", terminalA))).isEqualTo(200);
        assertThat(status("PUT", "/vehicles/" + vehicleB + "/terminal", token,
                Map.of("terminalId", terminalB))).isEqualTo(200);
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        insertAlarm(vehicleA, terminalA, fleet, "ACTIVE", "PENDING", now.minusMinutes(5));
        insertAlarm(vehicleB, terminalB, fleet, "ACTIVE", "PENDING", now.minusMinutes(1));

        JsonNode page = get(token, "/alarms?fleetId=" + fleet);
        List<String> pagePlates = new ArrayList<>();
        page.forEach(row -> pagePlates.add(row.path("plateNo").asText()));

        BinaryCall file = download(token, "/reports/exports/alarms.xlsx?fleetId=" + fleet);
        assertThat(file.status()).as("下载导出文件").isEqualTo(200);
        assertThat(file.header("content-type")).isEqualTo(XLSX);

        List<List<String>> rows = read(file.body());
        assertThat(rows.getFirst()).containsExactly(
                "车牌", "车队", "来源", "报警类型", "条件状态", "处置状态", "开始时间", "最后时间", "恢复时间");
        assertThat(column(rows, 0)).containsExactly(plateB, plateA);
        assertThat(column(rows, 0)).isEqualTo(pagePlates);
        assertThat(rows).hasSize(page.size() + 1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM alarm WHERE fleet_id_at_event=?", Long.class, fleet))
                .isEqualTo((long) page.size());
    }

    @Test
    @Order(3)
    void handlingRecordExportMatchesThePageHistoryAndDatabase() throws Exception {
        String token = login("export-admin", "Export-Pass-2026");
        String suffix = suffix();
        long fleet = create(token, "/fleets", Map.of("fleetCode", "HND" + suffix, "fleetName", "处置导出车队" + suffix));
        String plate = "处出" + suffix;
        long vehicle = create(token, "/vehicles", Map.of("plateNo", plate, "fleetId", fleet));
        long terminal = create(token, "/terminals", Map.of("terminalNo", terminalNo()));
        assertThat(status("PUT", "/vehicles/" + vehicle + "/terminal", token,
                Map.of("terminalId", terminal))).isEqualTo(200);
        long alarmId = insertAlarm(vehicle, terminal, fleet, "ACTIVE", "PENDING",
                LocalDateTime.now(clock).withNano(0));
        assertThat(status("POST", "/alarms/" + alarmId + "/handle", token,
                Map.of("targetStatus", "PROCESSING", "remark", "开始核查"))).isEqualTo(200);
        assertThat(status("POST", "/alarms/" + alarmId + "/handle", token,
                Map.of("targetStatus", "HANDLED", "remark", "处理完成"))).isEqualTo(200);

        JsonNode detail = get(token, "/alarms/" + alarmId);
        List<String> pageRemarks = new ArrayList<>();
        detail.path("handleHistory").forEach(log -> pageRemarks.add(log.path("remark").asText()));

        BinaryCall file = download(token, "/reports/exports/handling-records.xlsx?fleetId=" + fleet);
        assertThat(file.status()).as("下载导出文件").isEqualTo(200);
        assertThat(file.header("content-type")).isEqualTo(XLSX);

        List<List<String>> rows = read(file.body());
        assertThat(rows.getFirst()).containsExactly(
                "车牌", "车队", "变更类型", "原状态", "新状态", "操作人", "备注", "时间");
        assertThat(rows).hasSize(3);
        assertThat(column(rows, 0)).containsExactly(plate, plate);
        assertThat(column(rows, 2)).containsExactly("HANDLE", "HANDLE");
        assertThat(column(rows, 3)).containsExactly("PENDING", "PROCESSING");
        assertThat(column(rows, 4)).containsExactly("PROCESSING", "HANDLED");
        assertThat(column(rows, 5)).containsExactly("export-admin", "export-admin");
        assertThat(column(rows, 6)).isEqualTo(pageRemarks).containsExactly("开始核查", "处理完成");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM alarm_handle_log l JOIN alarm a ON a.id=l.alarm_id
                WHERE a.fleet_id_at_event=?
                """, Long.class, fleet)).isEqualTo(2L);
    }

    @Test
    @Order(4)
    void exportRowLimitRejectsOverLimitWithExplicitErrorForAllThreeTypes() throws Exception {
        String token = login("export-admin", "Export-Pass-2026");
        String suffix = suffix();
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        long fleetV = create(token, "/fleets", Map.of("fleetCode", "BND" + suffix, "fleetName", "边界车辆" + suffix));
        bulkVehicles(fleetV, "边车" + suffix.substring(0, 4), 10_000);
        BinaryCall vehiclesOk = download(token, "/reports/exports/vehicles.xlsx?fleetId=" + fleetV);
        assertThat(vehiclesOk.status()).as("10000 行车辆导出").isEqualTo(200);
        assertThat(read(vehiclesOk.body())).hasSize(10_001);
        bulkVehicles(fleetV, "超车" + suffix.substring(0, 4), 1);
        assertLimitError(token, "/reports/exports/vehicles.xlsx?fleetId=" + fleetV, "VEHICLES", 10_001);

        long fleetA = create(token, "/fleets", Map.of("fleetCode", "BNA" + suffix, "fleetName", "边界报警" + suffix));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", "边报" + suffix, "fleetId", fleetA));
        long terminal = create(token, "/terminals", Map.of("terminalNo", terminalNo()));
        assertThat(status("PUT", "/vehicles/" + vehicle + "/terminal", token,
                Map.of("terminalId", terminal))).isEqualTo(200);
        bulkRecoveredAlarms(vehicle, terminal, fleetA, 10_000, now);
        BinaryCall alarmsOk = download(token, "/reports/exports/alarms.xlsx?fleetId=" + fleetA);
        assertThat(alarmsOk.status()).as("10000 行报警导出").isEqualTo(200);
        assertThat(read(alarmsOk.body())).hasSize(10_001);
        bulkRecoveredAlarms(vehicle, terminal, fleetA, 1, now);
        assertLimitError(token, "/reports/exports/alarms.xlsx?fleetId=" + fleetA, "ALARMS", 10_001);

        bulkConditionLogs(fleetA, 0, 10_000, now);
        BinaryCall handlingOk = download(token, "/reports/exports/handling-records.xlsx?fleetId=" + fleetA);
        assertThat(handlingOk.status()).as("10000 行处置导出").isEqualTo(200);
        assertThat(read(handlingOk.body())).hasSize(10_001);
        bulkConditionLogs(fleetA, 10_000, 1, now);
        assertLimitError(token, "/reports/exports/handling-records.xlsx?fleetId=" + fleetA, "HANDLING", 10_001);
    }

    @Test
    @Order(5)
    void formulaLikeValuesAreEscapedAndDisplayedAsText() throws Exception {
        String token = login("export-admin", "Export-Pass-2026");
        String suffix = suffix();
        long fleet = create(token, "/fleets", Map.of("fleetCode", "FRM" + suffix, "fleetName", "公式车队" + suffix));
        String normal = "正常" + suffix;
        for (String plate : List.of(normal, "=1+1", "+86", "-2+3", "@SUM(A1)")) {
            create(token, "/vehicles", Map.of("plateNo", plate, "fleetId", fleet));
        }
        BinaryCall file = download(token, "/reports/exports/vehicles.xlsx?fleetId=" + fleet);
        assertThat(file.status()).as("下载导出文件").isEqualTo(200);
        List<String> plates = column(read(file.body()), 0);
        assertThat(plates).contains(normal)
                .contains("'" + "=1+1")
                .contains("'+86")
                .contains("'-2+3")
                .contains("'@SUM(A1)")
                .doesNotContain("=1+1", "+86", "-2+3", "@SUM(A1)");
    }

    @Test
    @Order(6)
    void fleetLeaderExportsOnlyAuthorizedFleetsAndTamperingIsRejected() throws Exception {
        String token = login("export-admin", "Export-Pass-2026");
        String suffix = suffix();
        long fleetA = create(token, "/fleets", Map.of("fleetCode", "LDA" + suffix, "fleetName", "授权车队" + suffix));
        long fleetB = create(token, "/fleets", Map.of("fleetCode", "LDB" + suffix, "fleetName", "越权车队" + suffix));
        String plateA = "授权" + suffix;
        String plateB = "越权" + suffix;
        long vehicleA = create(token, "/vehicles", Map.of("plateNo", plateA, "fleetId", fleetA));
        long vehicleB = create(token, "/vehicles", Map.of("plateNo", plateB, "fleetId", fleetB));
        long terminalA = create(token, "/terminals", Map.of("terminalNo", terminalNo()));
        long terminalB = create(token, "/terminals", Map.of("terminalNo", terminalNo()));
        assertThat(status("PUT", "/vehicles/" + vehicleA + "/terminal", token,
                Map.of("terminalId", terminalA))).isEqualTo(200);
        assertThat(status("PUT", "/vehicles/" + vehicleB + "/terminal", token,
                Map.of("terminalId", terminalB))).isEqualTo(200);
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        insertAlarm(vehicleA, terminalA, fleetA, "ACTIVE", "PENDING", now);
        insertAlarm(vehicleB, terminalB, fleetB, "ACTIVE", "PENDING", now);

        String leaderName = "export-leader-" + suffix;
        create(token, "/users", Map.of("username", leaderName, "password", "Leader-Pass-2026",
                "roleCode", "FLEET_LEADER", "fleetIds", List.of(fleetA)));
        String leader = login(leaderName, "Leader-Pass-2026");

        BinaryCall vehicles = download(leader, "/reports/exports/vehicles.xlsx");
        assertThat(vehicles.status()).isEqualTo(200);
        assertThat(column(read(vehicles.body()), 0)).containsExactly(plateA);
        BinaryCall alarms = download(leader, "/reports/exports/alarms.xlsx");
        assertThat(alarms.status()).isEqualTo(200);
        assertThat(column(read(alarms.body()), 0)).containsExactly(plateA);

        for (String path : List.of("/reports/exports/vehicles.xlsx?fleetId=" + fleetB,
                "/reports/exports/alarms.xlsx?fleetId=" + fleetB,
                "/reports/exports/handling-records.xlsx?fleetId=" + fleetB)) {
            BinaryCall denied = download(leader, path);
            assertThat(denied.status()).as(path).isEqualTo(403);
            assertThat(json.readTree(denied.body()).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(denied.header("content-disposition")).isEmpty();
        }
        Map<String, Object> failure = jdbc.queryForMap("""
                SELECT result, detail FROM audit_log
                WHERE category='EXPORT' AND actor_name=? ORDER BY id DESC LIMIT 1
                """, leaderName);
        assertThat(failure.get("result")).isEqualTo("FAILURE");
        assertThat((String) failure.get("detail")).contains("reason=FORBIDDEN");
    }

    @Test
    @Order(7)
    void exportAuditRecordsFiltersRowsAndResultWithoutContent() throws Exception {
        String token = login("export-admin", "Export-Pass-2026");
        String suffix = suffix();
        long fleet = create(token, "/fleets", Map.of("fleetCode", "AUD" + suffix, "fleetName", "审计车队" + suffix));
        String plateX = "审A" + suffix;
        String plateY = "审B" + suffix;
        create(token, "/vehicles", Map.of("plateNo", plateX, "fleetId", fleet));
        create(token, "/vehicles", Map.of("plateNo", plateY, "fleetId", fleet));
        long before = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM audit_log", Long.class);
        BinaryCall file = download(token, "/reports/exports/vehicles.xlsx?fleetId=" + fleet);
        assertThat(file.status()).isEqualTo(200);
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_name, result, detail FROM audit_log
                WHERE category='EXPORT' AND id>? ORDER BY id DESC LIMIT 1
                """, before);
        assertThat(audit.get("actor_name")).isEqualTo("export-admin");
        assertThat(audit.get("result")).isEqualTo("SUCCESS");
        String detail = (String) audit.get("detail");
        assertThat(detail).contains("type=VEHICLES").contains("fleetId=" + fleet).contains("rows=2");
        assertThat(detail).doesNotContain(plateX).doesNotContain(plateY);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log WHERE category='EXPORT' AND id>?
                AND (detail LIKE ? OR detail LIKE ?)
                """, Long.class, before, "%" + plateX + "%", "%" + plateY + "%")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log WHERE category='EXPORT' AND result='FAILURE'
                AND (detail LIKE '%reason=FORBIDDEN%' OR detail LIKE '%reason=ROW_LIMIT%')
                """, Long.class)).isGreaterThan(0L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log WHERE category='EXPORT'
                AND (detail LIKE '%satoken%' OR detail LIKE '%password%' OR detail LIKE '%authCode%')
                """, Long.class)).isZero();
    }

    @Test
    @Order(8)
    void databaseFailureReturnsExplicitErrorWithoutPartialFile() throws Exception {
        String token = login("export-admin", "Export-Pass-2026");
        long before = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM audit_log", Long.class);
        jdbc.execute("DROP TABLE alarm_handle_log");
        BinaryCall call = download(token, "/reports/exports/handling-records.xlsx");
        assertThat(call.status()).as("数据库失败时返回明确错误").isEqualTo(500);
        assertThat(call.header("content-disposition")).doesNotStartWith("attachment").doesNotContain(".xlsx");
        assertThat(call.header("content-type")).doesNotContain("spreadsheet");
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT result, detail FROM audit_log
                WHERE category='EXPORT' AND id>? ORDER BY id DESC LIMIT 1
                """, before);
        assertThat(audit.get("result")).isEqualTo("FAILURE");
        assertThat((String) audit.get("detail")).contains("type=HANDLING").contains("rows=0")
                .contains("reason=QUERY_FAILED");
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void bulkVehicles(long fleetId, String prefix, int count) {
        LocalDateTime at = LocalDateTime.now(clock).withNano(0);
        List<Object[]> args = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            args.add(new Object[] {prefix + String.format("%05d", index), fleetId, at, at});
        }
        jdbc.batchUpdate("""
                INSERT INTO vehicle (plate_no, fleet_id, status, created_at, updated_at)
                VALUES (?, ?, 'ENABLED', ?, ?)
                """, args);
    }

    private void bulkRecoveredAlarms(long vehicleId, long terminalId, long fleetId, int count,
                                     LocalDateTime start) {
        List<Object[]> args = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            args.add(new Object[] {vehicleId, terminalId, fleetId, start, start, start, start, start});
        }
        jdbc.batchUpdate("""
                INSERT INTO alarm (vehicle_id, terminal_id, driver_id, fleet_id_at_event, source, alarm_type,
                    business_dimension, condition_state, handle_status, rule_id, rule_version, rule_snapshot,
                    start_time, last_time, created_at, updated_at, recovered_at)
                VALUES (?, ?, NULL, ?, 'PLATFORM', 'OVERSPEED', '', 'RECOVERED', 'PENDING', NULL, NULL, NULL,
                    ?, ?, ?, ?, ?)
                """, args);
    }

    private void bulkConditionLogs(long fleetId, int offset, int count, LocalDateTime at) {
        List<Long> alarmIds = jdbc.queryForList("""
                SELECT id FROM alarm WHERE fleet_id_at_event=? ORDER BY id LIMIT ? OFFSET ?
                """, Long.class, fleetId, count, offset);
        List<Object[]> args = new ArrayList<>(alarmIds.size());
        for (Long alarmId : alarmIds) {
            args.add(new Object[] {alarmId, at});
        }
        jdbc.batchUpdate("""
                INSERT INTO alarm_handle_log (alarm_id, change_type, from_status, to_status, operator_id,
                    remark, created_at)
                VALUES (?, 'CONDITION', 'ACTIVE', 'RECOVERED', NULL, '条件恢复', ?)
                """, args);
    }

    private void assertLimitError(String token, String path, String type, long rows) throws Exception {
        BinaryCall call = download(token, path);
        assertThat(call.status()).as(path).isEqualTo(400);
        JsonNode body = json.readTree(call.body());
        assertThat(body.path("code").asText()).isEqualTo("EXPORT_ROW_LIMIT_EXCEEDED");
        assertThat(body.path("detail").asText()).contains("缩小筛选范围");
        assertThat(call.header("content-disposition")).isEmpty();
        assertThat(call.header("content-type")).doesNotContain("spreadsheet");
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT result, detail FROM audit_log
                WHERE category='EXPORT' AND target=? ORDER BY id DESC LIMIT 1
                """, "export:" + type);
        assertThat(audit.get("result")).isEqualTo("FAILURE");
        assertThat((String) audit.get("detail")).contains("rows=" + rows).contains("reason=ROW_LIMIT");
    }

    private String terminalNo() {
        return "0138001391" + String.format("%010d", SEQUENCE.getAndIncrement());
    }

    private int status(String method, String path, String token, Object body) throws Exception {
        return call(method, path, token, body).status();
    }

    private long insertAlarm(long vehicleId, long terminalId, long fleetId, String conditionState,
                             String handleStatus, LocalDateTime start) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO alarm (vehicle_id, terminal_id, driver_id, fleet_id_at_event, source, alarm_type,
                        business_dimension, condition_state, handle_status, rule_id, rule_version, rule_snapshot,
                        start_time, last_time, created_at, updated_at, recovered_at)
                    VALUES (?, ?, NULL, ?, 'PLATFORM', 'OVERSPEED', '', ?, ?, NULL, NULL, NULL, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, vehicleId);
            ps.setLong(2, terminalId);
            ps.setLong(3, fleetId);
            ps.setString(4, conditionState);
            ps.setString(5, handleStatus);
            ps.setObject(6, start);
            ps.setObject(7, start);
            ps.setObject(8, start);
            ps.setObject(9, start);
            ps.setObject(10, "RECOVERED".equals(conditionState) ? start : null);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private String login(String username, String password) throws Exception {
        HttpCall call = call("POST", "/auth/login", null, Map.of("username", username, "password", password));
        assertThat(call.status()).isEqualTo(200);
        return call.body().path("token").asText();
    }

    private long create(String token, String path, Object body) throws Exception {
        HttpCall call = call("POST", path, token, body);
        assertThat(call.status()).as("创建 %s 失败：%s", path, call.raw()).isEqualTo(201);
        return call.body().path("id").asLong();
    }

    private JsonNode get(String token, String path) throws Exception {
        HttpCall call = call("GET", path, token, null);
        assertThat(call.status()).as("GET %s 失败：%s", path, call.raw()).isEqualTo(200);
        return call.body();
    }

    private HttpCall call(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + httpPort + "/api/v1" + path))
                .timeout(Duration.ofSeconds(30));
        if (token != null) {
            builder.header("satoken", token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),
                    StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode node = response.body() == null || response.body().isBlank() ? null : json.readTree(response.body());
        return new HttpCall(response.statusCode(), node, response.body());
    }

    private BinaryCall download(String token, String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + httpPort + "/api/v1" + path))
                .timeout(Duration.ofSeconds(60));
        if (token != null) {
            builder.header("satoken", token);
        }
        HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return new BinaryCall(response.statusCode(), response.body(), response);
    }

    private List<List<String>> read(byte[] xlsx) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            List<List<String>> rows = new ArrayList<>();
            for (Row row : sheet) {
                List<String> values = new ArrayList<>();
                for (int index = 0; index < row.getLastCellNum(); index++) {
                    Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (cell == null) {
                        values.add("");
                        continue;
                    }
                    assertThat(cell.getCellType())
                            .as("第 %d 行第 %d 列必须是文本，不能是公式", row.getRowNum() + 1, index + 1)
                            .isNotEqualTo(CellType.FORMULA);
                    values.add(cell.getStringCellValue());
                }
                rows.add(values);
            }
            return rows;
        }
    }

    private List<String> column(List<List<String>> rows, int index) {
        List<String> values = new ArrayList<>();
        for (int row = 1; row < rows.size(); row++) {
            values.add(rows.get(row).get(index));
        }
        return values;
    }

    record HttpCall(int status, JsonNode body, String raw) { }

    record BinaryCall(int status, byte[] body, HttpResponse<byte[]> response) {
        String header(String name) {
            return response.headers().firstValue(name).orElse("");
        }
    }
}
