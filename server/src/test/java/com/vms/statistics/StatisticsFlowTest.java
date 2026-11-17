package com.vms.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.vms.access.TerminalAccessPort;
import com.vms.protocol.BodyProperties;
import com.vms.protocol.Frame;
import com.vms.protocol.FrameCodec;
import com.vms.protocol.MessageHeader;
import com.vms.protocol.TerminalAuthentication;
import com.vms.protocol.TerminalGeneralResponse;
import com.vms.protocol.TerminalRegister;
import com.vms.protocol.TerminalRegisterResponse;
import com.vms.support.IntegrationContainers;
import com.vms.support.MutableClock;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 19：有权限的监控统计。经真实 HTTP 边界以三角色、两车队构造数据，并与真实 MySQL 直查对照。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "vms.bootstrap-admin.username=stats-admin",
        "vms.bootstrap-admin.password=Stats-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0",
        "vms.statistics.startup-grace-seconds=0"
})
@Import({IntegrationContainers.class, StatisticsFlowTest.TimeConfiguration.class})
class StatisticsFlowTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);
    private static final AtomicInteger ACCOUNT_SEQUENCE = new AtomicInteger(1);
    private static final String ADMIN = "stats-admin";
    private static final String ADMIN_PASSWORD = "Stats-Pass-2026";
    private static final String IMEI = "012345678901234";
    private static final String VERSION = "00000000000000000001";

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired TerminalAccessPort access;
    @LocalServerPort int httpPort;

    @Test
    void todayMetricsUseShanghaiDayWindowAndDoNotLeakAcrossDays() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        Bound bound = fleetWithVehicleAndTerminal(token, "今日边界");
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        LocalDate today = now.toLocalDate();
        insertAlarm(bound, today.minusDays(1).atTime(LocalTime.of(23, 59, 59)), "PENDING");
        insertAlarm(bound, today.atStartOfDay(), "PROCESSING");
        insertAlarm(bound, today.atTime(9, 59), "HANDLED");
        insertAlarm(bound, today.plusDays(1).atTime(8, 0), "IGNORED");

        JsonNode overview = get(token, "/statistics/overview?fleetId=" + bound.fleetId());

        assertThat(overview.path("timezone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(overview.path("today").asText()).isEqualTo("2026-06-15");
        assertThat(overview.path("startTimeFrom").asText()).isEqualTo("2026-06-15T00:00:00+08:00");
        // 其他用例可能推进共享时钟，结束时间按请求前的可控时钟换算，仍验证“今日窗口止于当前”
        String expectedTo = now.atZone(clock.getZone())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
        assertThat(overview.path("startTimeTo").asText()).isEqualTo(expectedTo);
        assertThat(overview.path("vehicles").path("total").asLong()).isEqualTo(1);
        assertThat(overview.path("alarms").path("total").asLong())
                .as("昨日最后一毫秒与次日数据不进入今日").isEqualTo(2);

        assertThat(status("GET", "/statistics/overview", null, null)).isEqualTo(401);
    }

    @Test
    void handlingRateUsesSameBatchAndReportsNullWhenDenominatorIsZero() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        Bound bound = fleetWithVehicleAndTerminal(token, "处理率");
        LocalDate today = LocalDate.now(clock);
        insertAlarm(bound, today.atTime(8, 0), "PENDING");
        insertAlarm(bound, today.atTime(8, 10), "PROCESSING");
        insertAlarm(bound, today.atTime(8, 20), "HANDLED");
        insertAlarm(bound, today.atTime(8, 30), "IGNORED");
        insertAlarm(bound, today.minusDays(1).atTime(8, 0), "HANDLED");

        JsonNode alarms = get(token, "/statistics/overview?fleetId=" + bound.fleetId()).path("alarms");
        assertThat(alarms.path("total").asLong()).isEqualTo(4);
        assertThat(alarms.path("pending").asLong()).isEqualTo(1);
        assertThat(alarms.path("processing").asLong()).isEqualTo(1);
        assertThat(alarms.path("handled").asLong()).isEqualTo(1);
        assertThat(alarms.path("ignored").asLong()).isEqualTo(1);
        JsonNode rate = alarms.path("handlingRate");
        assertThat(rate.path("numerator").asLong()).as("分子只算同批 HANDLED+IGNORED").isEqualTo(2);
        assertThat(rate.path("denominator").asLong()).as("分母为同批创建总数").isEqualTo(4);
        assertThat(rate.path("percent").asDouble()).isEqualTo(50.0);
        assertThat(rate.path("note").asText()).isEqualTo("含误报");

        Bound empty = fleetWithVehicleAndTerminal(token, "零分母");
        JsonNode zero = get(token, "/statistics/overview?fleetId=" + empty.fleetId()).path("alarms");
        assertThat(zero.path("total").asLong()).isZero();
        assertThat(zero.path("handlingRate").path("denominator").asLong()).isZero();
        assertThat(zero.path("handlingRate").path("percent").isNull())
                .as("零分母返回 null，页面显示 —，不得为 0 或 NaN").isTrue();
        assertThat(zero.path("handlingRate").path("note").asText()).isEqualTo("含误报");
    }

    @Test
    void leaderOnlySeesAuthorizedFleetAndOutOfScopeFleetIsForbidden() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        Bound authorized = fleetWithVehicleAndTerminal(admin, "授权甲");
        Bound other = fleetWithVehicleAndTerminal(admin, "越权乙");
        LocalDate today = LocalDate.now(clock);
        insertAlarm(authorized, today.atTime(8, 0), "PENDING");
        insertAlarm(other, today.atTime(8, 0), "HANDLED");
        String leader = leaderToken(admin, authorized.fleetId());

        JsonNode overview = get(leader, "/statistics/overview");
        assertThat(overview.path("vehicles").path("total").asLong()).isEqualTo(1);
        assertThat(overview.path("alarms").path("total").asLong()).isEqualTo(1);
        assertThat(get(leader, "/statistics/overview?fleetId=" + authorized.fleetId())
                .path("alarms").path("total").asLong()).isEqualTo(1);

        HttpCall forbidden = call("GET", "/statistics/overview?fleetId=" + other.fleetId(), leader, null);
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.body().path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(status("GET", "/statistics/trend?fleetId=" + other.fleetId(), leader, null)).isEqualTo(403);
        assertThat(status("GET", "/statistics/types?fleetId=" + other.fleetId(), leader, null)).isEqualTo(403);
        assertThat(status("GET", "/statistics/fleets?fleetId=" + other.fleetId(), leader, null)).isEqualTo(403);

        JsonNode trend = get(leader, "/statistics/trend?startDate=" + today.minusDays(3) + "&endDate=" + today);
        long trendTotal = 0;
        for (JsonNode day : trend.path("days")) {
            trendTotal += day.path("alarms").path("total").asLong();
        }
        long mysqlTotal = jdbc.queryForObject("""
                SELECT COUNT(*) FROM alarm WHERE fleet_id_at_event=? AND start_time>=? AND start_time<=?
                """, Long.class, authorized.fleetId(), today.minusDays(3).atStartOfDay(),
                LocalDateTime.now(clock));
        assertThat(trendTotal).as("扩大日期区间也不能看到未授权车队").isEqualTo(1).isEqualTo(mysqlTotal);

        JsonNode fleets = get(leader, "/statistics/fleets");
        assertThat(fleets.path("fleets")).hasSize(1);
        assertThat(fleets.path("fleets").get(0).path("fleetId").asLong()).isEqualTo(authorized.fleetId());
        assertThat(fleets.path("totalAlarms").path("total").asLong()).isEqualTo(1);
        assertThat(fleets.path("totalVehicles").path("total").asLong()).isEqualTo(1);

        String monitor = monitorToken(admin);
        JsonNode adminOverview = get(admin, "/statistics/overview");
        JsonNode monitorOverview = get(monitor, "/statistics/overview");
        assertThat(monitorOverview.path("vehicles").path("total").asLong())
                .as("监控员按全车队范围统计车辆").isEqualTo(adminOverview.path("vehicles").path("total").asLong());
        assertThat(monitorOverview.path("alarms").path("total").asLong())
                .as("监控员按全车队范围统计报警").isEqualTo(adminOverview.path("alarms").path("total").asLong());
        assertThat(get(monitor, "/statistics/fleets").path("fleets").size())
                .as("监控员车队聚合覆盖全部车队").isEqualTo(get(admin, "/statistics/fleets").path("fleets").size());
    }

    @Test
    void historicalAlarmsStayWithFleetAtEventWhileVehiclesFollowCurrentArchive() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        Bound origin = fleetWithVehicleAndTerminal(admin, "历史归属原队");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long targetFleet = create(admin, "/fleets",
                Map.of("fleetCode", "T" + suffix, "fleetName", "历史归属新队" + suffix));
        LocalDate today = LocalDate.now(clock);
        insertAlarm(origin, today.atTime(7, 0), "HANDLED");
        // 换队要求生效时间严格晚于上一段区间起点（EffectiveTime.requireAfter），推进 1 秒后换队
        clock.advance(Duration.ofSeconds(1));
        HttpCall transfer = call("PUT", "/vehicles/" + origin.vehicleId() + "/fleet", admin,
                Map.of("fleetId", targetFleet));
        assertThat(transfer.status()).as("换队：%s", transfer.raw()).isEqualTo(200);

        String originLeader = leaderToken(admin, origin.fleetId());
        String targetLeader = leaderToken(admin, targetFleet);

        JsonNode originOverview = get(originLeader, "/statistics/overview?fleetId=" + origin.fleetId());
        assertThat(originOverview.path("vehicles").path("total").asLong())
                .as("车辆当前档案已离开原车队").isZero();
        assertThat(originOverview.path("alarms").path("total").asLong())
                .as("历史报警仍按 fleet_id_at_event 归属原车队").isEqualTo(1);

        JsonNode targetOverview = get(targetLeader, "/statistics/overview?fleetId=" + targetFleet);
        assertThat(targetOverview.path("vehicles").path("total").asLong())
                .as("当前车辆按当前档案计入新队").isEqualTo(1);
        assertThat(targetOverview.path("alarms").path("total").asLong())
                .as("历史报警不随车辆换队迁移").isZero();

        JsonNode fleets = get(admin, "/statistics/fleets?startDate=" + today + "&endDate=" + today);
        JsonNode originRow = fleetRow(fleets, origin.fleetId());
        JsonNode targetRow = fleetRow(fleets, targetFleet);
        assertThat(originRow.path("vehicles").path("total").asLong()).isZero();
        assertThat(originRow.path("alarms").path("total").asLong()).isEqualTo(1);
        assertThat(targetRow.path("vehicles").path("total").asLong()).isEqualTo(1);
        assertThat(targetRow.path("alarms").path("total").asLong()).isZero();
    }

    @Test
    void vehicleStatesAreSeparatedAndOfflineUsesCommittedFacts() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        long fleet = createFleet(admin, "状态分列");
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        Bound online = bindVehicle(admin, fleet, "在线");
        try (Socket socket = connect()) {
            String authCode = register(socket, online.terminalNo(), "ONLINE", 1);
            authenticate(socket, online.terminalNo(), authCode, 2);

            Bound offline = bindVehicle(admin, fleet, "离线");
            markTerminal(offline.terminalId(), true, now.minusSeconds(181));
            Bound active = bindVehicle(admin, fleet, "主动断开");
            markTerminal(active.terminalId(), true, now.minusSeconds(179));
            bindVehicle(admin, fleet, "未接入");
            Bound disabled = bindVehicle(admin, fleet, "停用");
            jdbc.update("UPDATE vehicle SET status='DISABLED' WHERE id=?", disabled.vehicleId());
            createVehicle(admin, fleet, "未绑定");

            JsonNode vehicles = get(admin, "/statistics/overview?fleetId=" + fleet).path("vehicles");
            assertThat(vehicles.path("total").asLong()).isEqualTo(6);
            assertThat(vehicles.path("online").asLong()).isEqualTo(1);
            assertThat(vehicles.path("offline").asLong()).as("181 秒未通信为离线").isEqualTo(1);
            assertThat(vehicles.path("activeDisconnected").asLong()).as("179 秒内断开单列").isEqualTo(1);
            assertThat(vehicles.path("notConnected").asLong()).isEqualTo(1);
            assertThat(vehicles.path("disabled").asLong()).isEqualTo(1);
            assertThat(vehicles.path("startupPending").asLong()).isZero();
            assertThat(vehicles.path("unbound").asLong()).isEqualTo(1);
            assertThat(vehicles.path("online").asLong() + vehicles.path("offline").asLong())
                    .as("在线加离线不必等于总数").isNotEqualTo(vehicles.path("total").asLong());
            long mysqlVehicles = jdbc.queryForObject("SELECT COUNT(*) FROM vehicle WHERE fleet_id=?",
                    Long.class, fleet);
            assertThat(vehicles.path("total").asLong()).isEqualTo(mysqlVehicles);
        }
    }

    @Test
    void trendTypesAndFleetViewsRecomputeFromTheSameTodayBatch() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        Bound first = fleetWithVehicleAndTerminal(admin, "复算车辆一");
        Bound second = bindVehicle(admin, first.fleetId(), "复算车辆二");
        Bound third = fleetWithVehicleAndTerminal(admin, "复算车辆三");
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        insertAlarm(first, today.atTime(8, 0), "PENDING");
        insertAlarm(first, today.atTime(8, 10), "PENDING");
        insertAlarm(second, today.atTime(8, 20), "PROCESSING");
        insertAlarm(second, today.atTime(8, 30), "HANDLED");
        insertAlarm(first, today.atTime(8, 40), "HANDLED");
        insertAlarm(first, today.atTime(8, 50), "IGNORED");
        insertAlarm(third, today.atTime(9, 0), "PENDING");
        insertAlarm(third, today.atTime(9, 10), "IGNORED");
        insertAlarm(first, today.minusDays(1).atTime(20, 0), "HANDLED");

        String fleetQuery = "&fleetId=" + first.fleetId();
        JsonNode overview = get(admin, "/statistics/overview?fleetId=" + first.fleetId()).path("alarms");
        assertSameMetrics(overview, overview);
        assertThat(overview.path("total").asLong()).isEqualTo(6);
        assertThat(overview.path("pending").asLong()).isEqualTo(2);
        assertThat(overview.path("processing").asLong()).isEqualTo(1);
        assertThat(overview.path("handled").asLong()).isEqualTo(2);
        assertThat(overview.path("ignored").asLong()).isEqualTo(1);

        JsonNode trend = get(admin, "/statistics/trend?startDate=" + today + "&endDate=" + today + fleetQuery);
        assertThat(trend.path("days")).hasSize(1);
        assertThat(trend.path("days").get(0).path("date").asText()).isEqualTo(today.toString());
        assertSameMetrics(trend.path("days").get(0).path("alarms"), overview);

        JsonNode types = get(admin, "/statistics/types?startDate=" + today + "&endDate=" + today + fleetQuery);
        assertThat(types.path("total").asLong()).isEqualTo(6);
        assertThat(types.path("types")).hasSize(1);
        assertThat(types.path("types").get(0).path("alarmType").asText()).isEqualTo("OVERSPEED");
        assertSameMetrics(types.path("types").get(0).path("alarms"), overview);

        JsonNode fleets = get(admin, "/statistics/fleets?startDate=" + today + "&endDate=" + today + fleetQuery);
        assertThat(fleets.path("fleets")).hasSize(1);
        assertThat(fleets.path("fleets").get(0).path("vehicles").path("total").asLong()).isEqualTo(2);
        assertSameMetrics(fleets.path("fleets").get(0).path("alarms"), overview);
        assertSameMetrics(fleets.path("totalAlarms"), overview);

        JsonNode overviewAll = get(admin, "/statistics/overview");
        JsonNode trendAll = get(admin, "/statistics/trend?startDate=" + today + "&endDate=" + today);
        assertSameMetrics(trendAll.path("days").get(0).path("alarms"), overviewAll.path("alarms"));
        JsonNode typesAll = get(admin, "/statistics/types?startDate=" + today + "&endDate=" + today);
        assertThat(typesAll.path("total").asLong()).isEqualTo(overviewAll.path("alarms").path("total").asLong());
        JsonNode fleetsAll = get(admin, "/statistics/fleets?startDate=" + today + "&endDate=" + today);
        assertSameMetrics(fleetsAll.path("totalAlarms"), overviewAll.path("alarms"));
        long fleetAlarmSum = 0;
        for (JsonNode row : fleetsAll.path("fleets")) {
            fleetAlarmSum += row.path("alarms").path("total").asLong();
        }
        assertThat(fleetAlarmSum).isEqualTo(overviewAll.path("alarms").path("total").asLong());
        assertThat(fleetsAll.path("totalVehicles").path("total").asLong())
                .isEqualTo(overviewAll.path("vehicles").path("total").asLong());

        long mysqlTotal = jdbc.queryForObject("""
                SELECT COUNT(*) FROM alarm WHERE fleet_id_at_event=? AND start_time>=? AND start_time<=?
                """, Long.class, first.fleetId(), today.atStartOfDay(), now);
        long mysqlSettled = jdbc.queryForObject("""
                SELECT COUNT(*) FROM alarm WHERE fleet_id_at_event=? AND start_time>=? AND start_time<=?
                    AND handle_status IN ('HANDLED','IGNORED')
                """, Long.class, first.fleetId(), today.atStartOfDay(), now);
        assertThat(mysqlTotal).isEqualTo(6);
        assertThat(mysqlSettled).isEqualTo(3);
        assertThat(overview.path("handlingRate").path("numerator").asLong()).isEqualTo(mysqlSettled);

        JsonNode trendRange = get(admin, "/statistics/trend?startDate=" + today.minusDays(1)
                + "&endDate=" + today + fleetQuery);
        assertThat(trendRange.path("days")).hasSize(2);
        assertThat(trendRange.path("days").get(0).path("alarms").path("total").asLong()).isEqualTo(1);
        assertThat(trendRange.path("days").get(1).path("alarms").path("total").asLong()).isEqualTo(6);
    }

    @Test
    void invalidDateRangesAreRejected() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        LocalDate today = LocalDate.now(clock);
        HttpCall inverted = call("GET", "/statistics/trend?startDate=" + today + "&endDate="
                + today.minusDays(1), admin, null);
        assertThat(inverted.status()).isEqualTo(400);
        assertThat(inverted.body().path("code").asText()).isEqualTo("INVALID_DATE_RANGE");
        assertThat(status("GET", "/statistics/types?endDate=" + today.plusDays(1), admin, null)).isEqualTo(400);
        assertThat(status("GET", "/statistics/fleets?startDate=" + today.minusDays(91) + "&endDate="
                + today, admin, null)).isEqualTo(400);
    }

    private record Bound(long fleetId, long vehicleId, long terminalId, String terminalNo) { }

    private String leaderToken(String adminToken, Long... fleetIds) throws Exception {
        int sequence = ACCOUNT_SEQUENCE.getAndIncrement();
        String username = "stats-leader" + sequence;
        String password = "Leader-Pass-" + sequence + "-2026";
        create(adminToken, "/users", Map.of("username", username, "password", password,
                "roleCode", "FLEET_LEADER", "fleetIds", List.of(fleetIds)));
        return login(username, password);
    }

    private String monitorToken(String adminToken) throws Exception {
        int sequence = ACCOUNT_SEQUENCE.getAndIncrement();
        String username = "stats-monitor" + sequence;
        String password = "Monitor-Pass-" + sequence + "-2026";
        create(adminToken, "/users", Map.of("username", username, "password", password,
                "roleCode", "MONITOR", "fleetIds", List.of()));
        return login(username, password);
    }

    private long createFleet(String token, String label) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return create(token, "/fleets", Map.of("fleetCode", "S" + suffix, "fleetName", label + suffix));
    }

    private long createVehicle(String token, long fleetId, String label) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return create(token, "/vehicles", Map.of("plateNo", label + suffix, "fleetId", fleetId));
    }

    private Bound bindVehicle(String token, long fleetId, String label) throws Exception {
        int sequence = SEQUENCE.getAndIncrement();
        long vehicle = createVehicle(token, fleetId, label);
        long terminal = create(token, "/terminals",
                Map.of("terminalNo", "0198000" + String.format("%013d", sequence)));
        int bound = status("PUT", "/vehicles/" + vehicle + "/terminal", token, Map.of("terminalId", terminal));
        assertThat(bound).as("绑定终端失败").isEqualTo(200);
        String terminalNo = jdbc.queryForObject("SELECT terminal_no FROM terminal WHERE id=?", String.class, terminal);
        return new Bound(fleetId, vehicle, terminal, terminalNo);
    }

    private Bound fleetWithVehicleAndTerminal(String token, String label) throws Exception {
        return bindVehicle(token, createFleet(token, label), label);
    }

    private JsonNode fleetRow(JsonNode fleets, long fleetId) {
        for (JsonNode row : fleets.path("fleets")) {
            if (row.path("fleetId").asLong() == fleetId) {
                return row;
            }
        }
        throw new AssertionError("车队聚合缺少 fleetId=" + fleetId);
    }

    private void assertSameMetrics(JsonNode actual, JsonNode expected) {
        for (String field : List.of("total", "pending", "processing", "handled", "ignored")) {
            assertThat(actual.path(field).asLong()).as(field).isEqualTo(expected.path(field).asLong());
        }
        JsonNode actualRate = actual.path("handlingRate");
        JsonNode expectedRate = expected.path("handlingRate");
        assertThat(actualRate.path("numerator").asLong()).isEqualTo(expectedRate.path("numerator").asLong());
        assertThat(actualRate.path("denominator").asLong()).isEqualTo(expectedRate.path("denominator").asLong());
        assertThat(actualRate.path("percent").isNull()).isEqualTo(expectedRate.path("percent").isNull());
        if (!expectedRate.path("percent").isNull()) {
            assertThat(actualRate.path("percent").asDouble()).isEqualTo(expectedRate.path("percent").asDouble());
        }
        assertThat(actualRate.path("note").asText()).isEqualTo(expectedRate.path("note").asText());
    }

    private long insertAlarm(Bound bound, LocalDateTime start, String handleStatus) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO alarm (vehicle_id, terminal_id, driver_id, fleet_id_at_event, source, alarm_type,
                        business_dimension, condition_state, handle_status, rule_id, rule_version, rule_snapshot,
                        start_time, last_time, created_at, updated_at, recovered_at)
                    VALUES (?, ?, NULL, ?, 'PLATFORM', 'OVERSPEED', '', 'EVENT', ?, NULL, NULL, NULL, ?, ?, ?, ?, NULL)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, bound.vehicleId());
            ps.setLong(2, bound.terminalId());
            ps.setLong(3, bound.fleetId());
            ps.setString(4, handleStatus);
            ps.setObject(5, start);
            ps.setObject(6, start);
            ps.setObject(7, start);
            ps.setObject(8, start);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private void markTerminal(long terminalId, boolean everAuthenticated, LocalDateTime lastSeen) {
        jdbc.update("UPDATE terminal SET ever_authenticated=?, last_seen_at=? WHERE id=?",
                everAuthenticated, lastSeen, terminalId);
    }

    private Socket connect() throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", access.port()), 5000);
        socket.setSoTimeout(3000);
        return socket;
    }

    private String register(Socket socket, String terminalNo, String suffix, int serial) throws Exception {
        send(socket, frame(terminalNo, 0x0100, serial,
                new TerminalRegister(1, 1, "ABC12", "TESTMODEL", terminalNo, 2, "湘A" + suffix).encode()));
        TerminalRegisterResponse response = TerminalRegisterResponse.parse(receive(socket).body());
        assertThat(response.result()).isZero();
        assertThat(response.authCode()).isNotBlank();
        return response.authCode();
    }

    private void authenticate(Socket socket, String terminalNo, String authCode, int serial) throws Exception {
        send(socket, frame(terminalNo, 0x0102, serial,
                new TerminalAuthentication(authCode, IMEI, VERSION).encode()));
        TerminalGeneralResponse response = TerminalGeneralResponse.parse(receive(socket).body());
        assertThat(response.responseId()).isEqualTo(0x0102);
        assertThat(response.result()).isZero();
    }

    private Frame frame(String terminalNo, int messageId, int serial, byte[] body) {
        return new Frame(new MessageHeader(messageId, BodyProperties.plain(body.length), 1, terminalNo, serial), body);
    }

    private void send(Socket socket, Frame frame) throws Exception {
        socket.getOutputStream().write(FrameCodec.encode(frame));
        socket.getOutputStream().flush();
    }

    private Frame receive(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[4096];
        int length = 0;
        while (true) {
            int read = in.read(buffer, length, buffer.length - length);
            if (read < 0) {
                throw new IllegalStateException("TCP listener closed before response");
            }
            length += read;
            var frames = FrameCodec.splitFrames(Arrays.copyOf(buffer, length));
            if (!frames.isEmpty()) {
                return FrameCodec.decode(frames.getFirst());
            }
        }
    }

    private String login(String username, String password) throws Exception {
        HttpCall call = call("POST", "/auth/login", null, Map.of("username", username, "password", password));
        assertThat(call.status()).as(call.raw()).isEqualTo(200);
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

    private int status(String method, String path, String token, Object body) throws Exception {
        return call(method, path, token, body).status();
    }

    private HttpCall call(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + httpPort + "/api/v1" + path))
                .timeout(Duration.ofSeconds(15));
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

    record HttpCall(int status, JsonNode body, String raw) { }

    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock statisticsClock() {
            return new MutableClock(Instant.parse("2026-06-15T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        }
    }
}
