package com.vms.track;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vms.infrastructure.ClockConfig;
import com.vms.support.IntegrationContainers;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "vms.bootstrap-admin.username=track-admin",
        "vms.bootstrap-admin.password=Track-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0"
})
@Import(IntegrationContainers.class)
@AutoConfigureMockMvc
class TrackQueryFlowTest {

    static final String ADMIN_NAME = "track-admin";
    static final String ADMIN_PASSWORD = "Track-Pass-2026";
    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void trackQueryReturnsOrderedDisplayPointsAndDoesNotModifyRawRows() throws Exception {
        String admin = login(ADMIN_NAME, ADMIN_PASSWORD);
        Bound bound = boundVehicle(admin);
        LocalDate day = businessToday().minusDays(1);
        insertPoint(bound, day.atTime(9, 0, 0), 121.1001, 31.2001);
        insertPoint(bound, day.atTime(9, 0, 10), 121.1002, 31.2002);
        insertPoint(bound, day.atTime(9, 0, 20), 121.1003, 31.2003);
        List<Map<String, Object>> before = jdbc.queryForList(
                "SELECT id, lng, lat, payload_hash, received_at FROM track_point WHERE vehicle_id = ? ORDER BY gps_time",
                bound.vehicleId());

        JsonNode view = read(admin, bound.vehicleId(), day.atTime(8, 0), day.atTime(10, 0));

        assertThat(view.path("vehicleId").asLong()).isEqualTo(bound.vehicleId());
        assertThat(view.path("plateNo").asText()).isEqualTo(bound.plateNo());
        assertThat(view.path("rawPointCount").asInt()).isEqualTo(3);
        assertThat(view.path("invalidPointCount").asInt()).isZero();
        assertThat(view.path("displayPointCount").asInt()).isEqualTo(2);
        assertThat(view.path("from").asText()).endsWith("+08:00");
        assertThat(view.path("segments").size()).isEqualTo(1);
        JsonNode segment = view.path("segments").get(0);
        assertThat(segment.path("startTime").asText()).endsWith("+08:00");
        assertThat(segment.path("points").size()).isEqualTo(2);
        assertThat(segment.path("points").get(0).path("lng").asDouble()).isEqualTo(121.1001);
        assertThat(segment.path("points").get(0).path("speedKph").asDouble()).isEqualTo(10.0);
        assertThat(segment.path("points").get(0).path("directionDeg").asInt()).isEqualTo(90);
        assertThat(segment.path("points").get(0).path("eventRelated").asBoolean()).isFalse();
        assertThat(segment.path("points").get(0).path("gpsTime").asText()).endsWith("+08:00");
        assertThat(segment.path("points").get(1).path("lng").asDouble()).isEqualTo(121.1003);
        assertThat(view.path("gaps").size()).isZero();

        List<Map<String, Object>> after = jdbc.queryForList(
                "SELECT id, lng, lat, payload_hash, received_at FROM track_point WHERE vehicle_id = ? ORDER BY gps_time",
                bound.vehicleId());
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT action, result, target FROM audit_log WHERE category = 'TRACK' "
                        + "AND action = 'QUERY' ORDER BY id DESC LIMIT 1"))
                .containsEntry("action", "QUERY")
                .containsEntry("result", "SUCCESS")
                .containsEntry("target", "vehicle:" + bound.vehicleId());
    }

    @Test
    void trackQueryAllowsCrossDayWithin24Hours() throws Exception {
        String admin = login(ADMIN_NAME, ADMIN_PASSWORD);
        Bound bound = boundVehicle(admin);
        LocalDate today = businessToday();
        LocalDate yesterday = today.minusDays(1);
        insertPoint(bound, yesterday.atTime(23, 59, 50), 121.201, 31.301);
        insertPoint(bound, today.atTime(0, 0, 10), 121.202, 31.302);

        JsonNode view = read(admin, bound.vehicleId(), yesterday.atTime(23, 0), today.atTime(1, 0));

        assertThat(view.path("rawPointCount").asInt()).isEqualTo(2);
        assertThat(view.path("segments").size()).isEqualTo(1);
        assertThat(view.path("segments").get(0).path("points").size()).isEqualTo(2);
        assertThat(view.path("gaps").size()).isZero();
    }

    @Test
    void trackQueryRejectsOversizedReversedMalformedAndExpiredWindows() throws Exception {
        String admin = login(ADMIN_NAME, ADMIN_PASSWORD);
        Bound bound = boundVehicle(admin);
        LocalDate today = businessToday();
        LocalDate yesterday = today.minusDays(1);

        mvc.perform(get("/api/v1/tracks").header("satoken", admin)
                        .param("vehicleId", Long.toString(bound.vehicleId()))
                        .param("from", yesterday.atStartOfDay().toString())
                        .param("to", today.atStartOfDay().plusSeconds(1).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("TRACK_WINDOW_EXCEEDS_24H"));

        mvc.perform(get("/api/v1/tracks").header("satoken", admin)
                        .param("vehicleId", Long.toString(bound.vehicleId()))
                        .param("from", yesterday.atStartOfDay().toString())
                        .param("to", today.atStartOfDay().toString()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/tracks").header("satoken", admin)
                        .param("vehicleId", Long.toString(bound.vehicleId()))
                        .param("from", yesterday.atTime(10, 0).toString())
                        .param("to", yesterday.atTime(9, 0).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("TRACK_WINDOW_INVALID"));

        mvc.perform(get("/api/v1/tracks").header("satoken", admin)
                        .param("vehicleId", Long.toString(bound.vehicleId()))
                        .param("from", "not-a-time")
                        .param("to", yesterday.atTime(10, 0).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("TRACK_TIME_INVALID"));

        mvc.perform(get("/api/v1/tracks").header("satoken", admin)
                        .param("vehicleId", Long.toString(bound.vehicleId()))
                        .param("from", today.minusDays(40).atTime(8, 0).toString())
                        .param("to", today.minusDays(40).atTime(9, 0).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("TRACK_OUTSIDE_RETENTION"));

        mvc.perform(get("/api/v1/tracks").header("satoken", admin)
                        .param("from", yesterday.atTime(8, 0).toString())
                        .param("to", yesterday.atTime(9, 0).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("TRACK_VEHICLE_REQUIRED"));
    }

    @Test
    void invalidFixAndLongGapSplitSegmentsAndReportGaps() throws Exception {
        String admin = login(ADMIN_NAME, ADMIN_PASSWORD);
        Bound bound = boundVehicle(admin);
        LocalDate day = businessToday().minusDays(1);
        insertPoint(bound, day.atTime(9, 0, 0), 121.3001, 31.4001);
        insertPoint(bound, day.atTime(9, 0, 10), null, null, 0L, "INVALID");
        insertPoint(bound, day.atTime(9, 0, 20), 121.3003, 31.4003);
        insertPoint(bound, day.atTime(9, 2, 0), 121.3004, 31.4004);

        JsonNode view = read(admin, bound.vehicleId(), day.atTime(9, 0), day.atTime(9, 5));

        assertThat(view.path("rawPointCount").asInt()).isEqualTo(4);
        assertThat(view.path("invalidPointCount").asInt()).isEqualTo(1);
        assertThat(view.path("segments").size()).isEqualTo(3);
        assertThat(view.path("segments").get(0).path("points").size()).isEqualTo(1);
        assertThat(view.path("segments").get(1).path("points").size()).isEqualTo(1);
        assertThat(view.path("segments").get(2).path("points").size()).isEqualTo(1);
        assertThat(view.path("gaps").size()).isEqualTo(2);
        assertThat(view.path("gaps").get(0).path("reason").asText()).isEqualTo("INVALID_FIX");
        assertThat(view.path("gaps").get(0).path("startTime").asText())
                .isEqualTo(view.path("segments").get(0).path("points").get(0).path("gpsTime").asText());
        assertThat(view.path("gaps").get(0).path("endTime").asText())
                .isEqualTo(view.path("segments").get(1).path("points").get(0).path("gpsTime").asText());
        assertThat(view.path("gaps").get(1).path("reason").asText()).isEqualTo("TIME_GAP");
        assertThat(view.path("gaps").get(1).path("endTime").asText())
                .isEqualTo(view.path("segments").get(2).path("points").get(0).path("gpsTime").asText());
        assertThat(view.path("segments").get(2).path("points").get(0).path("lng").asDouble()).isEqualTo(121.3004);
    }
    @Test
    void simplificationKeepsEventRelatedPointOnStraightLine() throws Exception {
        String admin = login(ADMIN_NAME, ADMIN_PASSWORD);
        Bound bound = boundVehicle(admin);
        LocalDate day = businessToday().minusDays(1);
        insertPoint(bound, day.atTime(10, 0, 0), 121.4001, 31.5001);
        insertPoint(bound, day.atTime(10, 0, 10), 121.4002, 31.5002, 1L, "VALID");
        insertPoint(bound, day.atTime(10, 0, 20), 121.4003, 31.5003);
        insertPoint(bound, day.atTime(10, 0, 30), 121.4004, 31.5004);

        JsonNode view = read(admin, bound.vehicleId(), day.atTime(10, 0), day.atTime(10, 5));

        assertThat(view.path("rawPointCount").asInt()).isEqualTo(4);
        assertThat(view.path("displayPointCount").asInt()).isEqualTo(3);
        JsonNode points = view.path("segments").get(0).path("points");
        assertThat(points.size()).isEqualTo(3);
        assertThat(points.get(1).path("eventRelated").asBoolean()).isTrue();
        assertThat(points.get(1).path("lng").asDouble()).isEqualTo(121.4002);
        assertThat(points.get(0).path("lng").asDouble()).isEqualTo(121.4001);
        assertThat(points.get(2).path("lng").asDouble()).isEqualTo(121.4004);
    }
    @Test
    void highDensityTraceIsSimplifiedUnderDisplayLimit() throws Exception {
        String admin = login(ADMIN_NAME, ADMIN_PASSWORD);
        Bound bound = boundVehicle(admin);
        LocalDate day = businessToday().minusDays(1);
        insertPoints(bound, day.atTime(0, 0, 0), 2500, 0L);

        JsonNode view = read(admin, bound.vehicleId(), day.atTime(0, 0), day.atTime(23, 59));

        assertThat(view.path("rawPointCount").asInt()).isEqualTo(2500);
        assertThat(view.path("displayPointCount").asInt()).isLessThanOrEqualTo(2000);
        JsonNode points = view.path("segments").get(0).path("points");
        assertThat(points.get(0).path("gpsTime").asText())
                .isEqualTo(day.atTime(0, 0).atZone(ClockConfig.BUSINESS_ZONE).toOffsetDateTime().format(
                        java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        assertThat(points.get(points.size() - 1).path("lng").asDouble())
                .isGreaterThan(points.get(0).path("lng").asDouble());
    }

    @Test
    void queryRejectsWhenMandatoryPointsExceedDisplayLimit() throws Exception {
        String admin = login(ADMIN_NAME, ADMIN_PASSWORD);
        Bound bound = boundVehicle(admin);
        LocalDate day = businessToday().minusDays(1);
        insertPoints(bound, day.atTime(0, 0, 0), 2001, 1L);

        mvc.perform(get("/api/v1/tracks").header("satoken", admin)
                        .param("vehicleId", Long.toString(bound.vehicleId()))
                        .param("from", day.atTime(0, 0).toString())
                        .param("to", day.atTime(23, 59).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("TRACK_TOO_DENSE").contains("缩小"));

        assertThat(jdbc.queryForMap("SELECT result, detail FROM audit_log WHERE category = 'TRACK' "
                        + "AND action = 'QUERY' AND target = ? ORDER BY id DESC LIMIT 1",
                        "vehicle:" + bound.vehicleId()))
                .containsEntry("result", "FAILURE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE vehicle_id = ?", Long.class,
                bound.vehicleId())).isEqualTo(2001);
    }

    @Test
    void leaderCanOnlyQueryVehiclesInGrantedFleetsAndDenialIsAudited() throws Exception {
        String admin = login(ADMIN_NAME, ADMIN_PASSWORD);
        Bound allowed = boundVehicle(admin);
        Bound denied = boundVehicle(admin);
        LocalDate day = businessToday().minusDays(1);
        insertPoint(allowed, day.atTime(9, 0, 0), 121.6001, 31.7001);
        insertPoint(denied, day.atTime(9, 0, 0), 121.6002, 31.7002);
        String name = "track-leader-" + UUID.randomUUID().toString().substring(0, 8);
        create(admin, "/users", Map.of("username", name, "password", "Leader-Pass-2026",
                "roleCode", "FLEET_LEADER", "fleetIds", List.of(allowed.fleetId())));
        String leader = login(name, "Leader-Pass-2026");

        JsonNode view = read(leader, allowed.vehicleId(), day.atTime(8, 0), day.atTime(10, 0));
        assertThat(view.path("vehicleId").asLong()).isEqualTo(allowed.vehicleId());

        mvc.perform(get("/api/v1/tracks").header("satoken", leader)
                        .param("vehicleId", Long.toString(denied.vehicleId()))
                        .param("from", day.atTime(8, 0).toString())
                        .param("to", day.atTime(10, 0).toString()))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForMap("SELECT result, detail FROM audit_log WHERE category = 'TRACK' "
                        + "AND action = 'QUERY' AND target = ? ORDER BY id DESC LIMIT 1",
                        "vehicle:" + denied.vehicleId()))
                .containsEntry("result", "FAILURE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE vehicle_id = ?", Long.class,
                denied.vehicleId())).isEqualTo(1);
    }
    private JsonNode read(String token, long vehicleId, LocalDateTime from, LocalDateTime to) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/tracks").header("satoken", token)
                        .param("vehicleId", Long.toString(vehicleId))
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private LocalDate businessToday() {
        return LocalDate.now(ClockConfig.BUSINESS_ZONE);
    }

    private void insertPoints(Bound bound, LocalDateTime start, int count, long alarmFlag) {
        List<Object[]> batch = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            LocalDateTime time = start.plusSeconds(index);
            batch.add(new Object[]{bound.terminalId(), bound.vehicleId(), bound.fleetId(), time, time,
                    121.5 + index * 0.000001, 31.6 + index * 0.000001, alarmFlag,
                    "0".repeat(64), new byte[]{1}});
        }
        jdbc.batchUpdate("""
                INSERT INTO track_point (terminal_id, vehicle_id, fleet_id_at_event, gps_time, received_at,
                    lng, lat, speed, direction, alarm_flag, status_flag, locate_state, report_type,
                    payload_hash, raw_body)
                VALUES (?, ?, ?, ?, ?, ?, ?, 10.0, 90, ?, 3, 'VALID', 'HISTORY', ?, ?)
                """, batch);
    }
    private void insertPoint(Bound bound, LocalDateTime gpsTime, Double lng, Double lat) {
        insertPoint(bound, gpsTime, lng, lat, 0L, "VALID");
    }

    private void insertPoint(Bound bound, LocalDateTime gpsTime, Double lng, Double lat,
                             long alarmFlag, String locateState) {
        jdbc.update("""
                INSERT INTO track_point (terminal_id, vehicle_id, fleet_id_at_event, gps_time, received_at,
                    lng, lat, speed, direction, alarm_flag, status_flag, locate_state, report_type,
                    payload_hash, raw_body)
                VALUES (?, ?, ?, ?, ?, ?, ?, 10.0, 90, ?, 3, ?, 'HISTORY', ?, ?)
                """, bound.terminalId(), bound.vehicleId(), bound.fleetId(), gpsTime, gpsTime,
                lng, lat, alarmFlag, locateState, "0".repeat(64), new byte[]{1});
    }

    private String login(String username, String password) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("token").asText();
    }

    private Bound boundVehicle(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = create(token, "/fleets", Map.of("fleetCode", "T" + suffix, "fleetName", "轨迹车队" + suffix));
        String plateNo = "轨迹" + suffix;
        long vehicle = create(token, "/vehicles", Map.of("plateNo", plateNo, "fleetId", fleet));
        long terminal = create(token, "/terminals", Map.of("terminalNo",
                "013800139000" + String.format("%08d", SEQUENCE.getAndIncrement())));
        return new Bound(terminal, vehicle, fleet, plateNo);
    }

    private long create(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1" + path).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("id").asLong();
    }

    private record Bound(long terminalId, long vehicleId, long fleetId, String plateNo) { }
}







