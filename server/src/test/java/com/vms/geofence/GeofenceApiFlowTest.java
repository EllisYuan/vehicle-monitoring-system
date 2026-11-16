package com.vms.geofence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vms.support.IntegrationContainers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "vms.bootstrap-admin.username=geofence-admin",
        "vms.bootstrap-admin.password=Geofence-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0"
})
@Import(IntegrationContainers.class)
@AutoConfigureMockMvc
class GeofenceApiFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    /** 同一测试上下文的数据库跨用例保留；每个用例从空围栏表开始，断言不依赖执行顺序。 */
    @BeforeEach
    void cleanGeofences() {
        jdbc.update("DELETE FROM geofence_vehicle_state");
        jdbc.update("DELETE FROM geofence_vehicle");
        jdbc.update("DELETE FROM geofence");
    }

    @Test
    void serverRejectsInvalidCircleAndPolygonGeometryWithSameRulesAsPage() throws Exception {
        String token = login("geofence-admin", "Geofence-Pass-2026");

        expectInvalid(token, circle("半径过小", 116.4, 39.9, 9.99));
        expectInvalid(token, circle("半径过大", 116.4, 39.9, 100_000.01));
        expectInvalid(token, circle("半径缺失", 116.4, 39.9, null));
        expectInvalid(token, circle("经度越界", 180.5, 39.9, 100.0));
        expectInvalid(token, circle("纬度越界", 116.4, -90.5, 100.0));
        expectInvalid(token, polygon("顶点不足", List.of(vertex(116.0, 39.0), vertex(116.01, 39.0))));
        expectInvalid(token, polygon("自交", List.of(vertex(116.0, 39.0), vertex(116.02, 39.02),
                vertex(116.02, 39.0), vertex(116.0, 39.02))));
        expectInvalid(token, polygon("退化", List.of(vertex(116.0, 39.0), vertex(116.01, 39.0),
                vertex(116.02, 39.0))));
        expectInvalid(token, polygon("重复点", List.of(vertex(116.0, 39.0), vertex(116.0, 39.0),
                vertex(116.01, 39.0), vertex(116.01, 39.01))));
        expectInvalid(token, polygon("顶点越界", List.of(vertex(116.0, 39.0), vertex(116.01, 39.0),
                vertex(116.01, 91.0))));
        expectInvalid(token, polygon("顶点过多", ring(21)));

        JsonNode smallest = create(token, circle("最小半径", 116.4, 39.9, 10.0));
        assertThat(smallest.path("fenceType").asText()).isEqualTo("CIRCLE");
        assertThat(smallest.path("radiusM").asDouble()).isEqualTo(10.0);
        assertThat(smallest.path("status").asText()).isEqualTo("ENABLED");
        assertThat(smallest.path("version").asLong()).isEqualTo(1L);
        assertThat(smallest.path("enterEnabled").asBoolean()).isTrue();
        assertThat(smallest.path("leaveEnabled").asBoolean()).isTrue();
        assertThat(smallest.path("vehicles").size()).isZero();

        assertThat(create(token, circle("最大半径", 116.4, 39.9, 100_000.0)).path("radiusM").asDouble())
                .isEqualTo(100_000.0);
        assertThat(create(token, polygon("三角形", List.of(vertex(116.0, 39.0), vertex(116.01, 39.0),
                vertex(116.0, 39.01)))).path("vertices").size()).isEqualTo(3);
        JsonNode twenty = create(token, polygon("二十边形", ring(20)));
        assertThat(twenty.path("vertices").size()).isEqualTo(20);
        assertThat(twenty.path("centerLng").isNull()).isTrue();
        assertThat(twenty.path("radiusM").isNull()).isTrue();

        List<String> names = jdbc.queryForList("SELECT fence_name FROM geofence ORDER BY fence_name", String.class);
        // 名称带随机后缀保证可重复运行；只允许合法用例落库，非法用例一条都不能写入。
        assertThat(names).hasSize(4);
        assertThat(names).allMatch(name -> name.startsWith("最小半径-") || name.startsWith("最大半径-")
                || name.startsWith("三角形-") || name.startsWith("二十边形-"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM geofence", Long.class)).isEqualTo(4L);
    }

    @Test
    void editDisableAndBindingAreVersionedAuditedAndGeometryStoredAsWgs84() throws Exception {
        String token = login("geofence-admin", "Geofence-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = createResource(token, "/fleets", Map.of("fleetCode", "G" + suffix, "fleetName", "围栏车队" + suffix));
        long vehicle = createResource(token, "/vehicles", Map.of("plateNo", "围栏" + suffix, "fleetId", fleet));

        Map<String, Object> createBody = circle("版本围栏", 116.4, 39.9, 500.0);
        createBody.put("vehicleIds", List.of(vehicle));
        JsonNode created = create(token, createBody);
        long id = created.path("id").asLong();
        assertThat(created.path("version").asLong()).isEqualTo(1L);
        assertThat(created.path("vehicles").size()).isEqualTo(1);
        assertThat(created.path("vehicles").get(0).path("id").asLong()).isEqualTo(vehicle);

        // 圆形以 WGS84 点保存，SRID 为 4326；坐标按经度/纬度原样入库。
        Map<String, Object> point = jdbc.queryForMap("""
                SELECT ST_SRID(center_point) AS srid, ST_Longitude(center_point) AS lng,
                       ST_Latitude(center_point) AS lat
                FROM geofence WHERE id = ?
                """, id);
        assertThat(((Number) point.get("srid")).intValue()).isEqualTo(4326);
        assertThat(((Number) point.get("lng")).doubleValue()).isCloseTo(116.4, within(1e-9));
        assertThat(((Number) point.get("lat")).doubleValue()).isCloseTo(39.9, within(1e-9));
        assertThat(jdbc.queryForObject("SELECT radius FROM geofence WHERE id = ?", Double.class, id))
                .isEqualTo(500.0);

        // 形状编辑：圆改为多边形并关闭离开事件，版本递增、绑定保持。
        Map<String, Object> editBody = polygon("版本围栏-多边形", ring(4));
        editBody.put("vehicleIds", List.of(vehicle));
        editBody.put("leaveEnabled", false);
        JsonNode edited = putJson(token, "/geofences/" + id, editBody);
        assertThat(edited.path("version").asLong()).isEqualTo(2L);
        assertThat(edited.path("fenceType").asText()).isEqualTo("POLYGON");
        assertThat(edited.path("centerLng").isNull()).isTrue();
        assertThat(edited.path("radiusM").isNull()).isTrue();
        assertThat(edited.path("leaveEnabled").asBoolean()).isFalse();
        assertThat(edited.path("enterEnabled").asBoolean()).isTrue();
        assertThat(edited.path("vehicles").size()).isEqualTo(1);

        Map<String, Object> boundary = jdbc.queryForMap("""
                SELECT ST_SRID(boundary) AS srid, ST_AsText(boundary, 'axis-order=long-lat') AS wkt
                FROM geofence WHERE id = ?
                """, id);
        assertThat(((Number) boundary.get("srid")).intValue()).isEqualTo(4326);
        assertThat((String) boundary.get("wkt")).startsWith("POLYGON((").contains("116.41 39.9");
        JsonNode storedVertices = json.readTree(jdbc.queryForObject(
                "SELECT vertices FROM geofence WHERE id = ?", String.class, id));
        assertThat(storedVertices.size()).isEqualTo(4);
        assertThat(storedVertices.get(0).path("lng").asDouble()).isEqualTo(116.41);
        assertThat(storedVertices.get(0).path("lat").asDouble()).isEqualTo(39.9);

        JsonNode disabled = putJson(token, "/geofences/" + id + "/status", Map.of("status", "DISABLED"));
        assertThat(disabled.path("status").asText()).isEqualTo("DISABLED");
        assertThat(disabled.path("version").asLong()).isEqualTo(3L);

        JsonNode unbound = putJson(token, "/geofences/" + id + "/vehicles",
                Map.of("vehicleIds", List.of()));
        assertThat(unbound.path("vehicles").size()).isZero();
        assertThat(unbound.path("version").asLong()).isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM geofence_vehicle WHERE geofence_id = ?",
                Long.class, id)).isZero();

        assertThat(jdbc.queryForList("""
                SELECT action FROM audit_log WHERE category = 'GEOFENCE' AND target = ? ORDER BY id
                """, String.class, "geofence:" + id))
                .containsExactly("CREATE", "UPDATE", "STATUS", "VEHICLES");

        // 重名在服务端拒绝，且失败操作不落库、不写成功审计。
        long auditBefore = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE category = 'GEOFENCE'", Long.class);
        String existingName = jdbc.queryForObject("SELECT fence_name FROM geofence WHERE id = ?", String.class, id);
        Map<String, Object> duplicate = circle("重名占位", 116.4, 39.9, 300.0);
        duplicate.put("fenceName", existingName);
        mvc.perform(post("/api/v1/geofences").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(duplicate)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE category = 'GEOFENCE'", Long.class))
                .isEqualTo(auditBefore);
    }
    @Test
    void monitorMaintainsGeofencesWhileFleetLeaderOnlySeesAuthorizedVehicleBindings() throws Exception {
        String token = login("geofence-admin", "Geofence-Pass-2026");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleetA = createResource(token, "/fleets", Map.of("fleetCode", "FA" + suffix, "fleetName", "甲队" + suffix));
        long fleetB = createResource(token, "/fleets", Map.of("fleetCode", "FB" + suffix, "fleetName", "乙队" + suffix));
        long vehicleA = createResource(token, "/vehicles", Map.of("plateNo", "甲" + suffix, "fleetId", fleetA));
        long vehicleB = createResource(token, "/vehicles", Map.of("plateNo", "乙" + suffix, "fleetId", fleetB));

        String monitorName = "geo-monitor-" + suffix;
        createResource(token, "/users", Map.of("username", monitorName, "password", "Monitor-Pass-2026",
                "roleCode", "MONITOR", "fleetIds", List.of()));
        String monitorToken = login(monitorName, "Monitor-Pass-2026");

        Map<String, Object> fenceABody = circle("甲队围栏", 116.4, 39.9, 500.0);
        fenceABody.put("vehicleIds", List.of(vehicleA));
        long fenceA = create(token, fenceABody).path("id").asLong();
        Map<String, Object> fenceBBody = circle("乙队围栏", 116.5, 39.9, 500.0);
        fenceBBody.put("vehicleIds", List.of(vehicleB));
        long fenceB = create(token, fenceBBody).path("id").asLong();
        Map<String, Object> monitorBody = circle("监控员围栏", 116.6, 39.9, 500.0);
        monitorBody.put("vehicleIds", List.of(vehicleA));
        JsonNode monitorCreated = create(monitorToken, monitorBody);
        assertThat(monitorCreated.path("version").asLong()).isEqualTo(1L);

        String leaderName = "geo-leader-" + suffix;
        createResource(token, "/users", Map.of("username", leaderName, "password", "Leader-Pass-2026",
                "roleCode", "FLEET_LEADER", "fleetIds", List.of(fleetA)));
        String leaderToken = login(leaderName, "Leader-Pass-2026");

        // 车队长列表只包含授权车队车辆的绑定，且不返回别队车辆。
        JsonNode leaderList = getJson(leaderToken, "/geofences");
        assertThat(leaderList.size()).isEqualTo(2);
        List<Long> visibleIds = new java.util.ArrayList<>();
        for (JsonNode fence : leaderList) {
            visibleIds.add(fence.path("id").asLong());
            for (JsonNode vehicle : fence.path("vehicles")) {
                assertThat(vehicle.path("fleetId").asLong()).isEqualTo(fleetA);
            }
        }
        assertThat(visibleIds).contains(fenceA, monitorCreated.path("id").asLong()).doesNotContain(fenceB);

        // 越权读取、指定他队过滤、创建/编辑/绑定都返回 403。
        expectForbidden(get("/api/v1/geofences/" + fenceB).header("satoken", leaderToken));
        expectForbidden(get("/api/v1/geofences?fleetId=" + fleetB).header("satoken", leaderToken));
        expectForbidden(post("/api/v1/geofences").header("satoken", leaderToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(circle("车队长围栏", 116.4, 39.9, 500.0))));
        expectForbidden(put("/api/v1/geofences/" + fenceA).header("satoken", leaderToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(circle("越权编辑", 116.4, 39.9, 500.0))));
        expectForbidden(put("/api/v1/geofences/" + fenceA + "/vehicles").header("satoken", leaderToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("vehicleIds", List.of(vehicleB)))));
        expectForbidden(put("/api/v1/geofences/" + fenceA + "/status").header("satoken", leaderToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("status", "DISABLED"))));
        assertThat(jdbc.queryForObject("SELECT status FROM geofence WHERE id = ?", String.class, fenceA))
                .isEqualTo("ENABLED");

        // 管理员按车队过滤，监控员可维护围栏。
        JsonNode adminFleetA = getJson(token, "/geofences?fleetId=" + fleetA);
        assertThat(adminFleetA.size()).isEqualTo(2);
        JsonNode disabled = putJson(monitorToken, "/geofences/" + fenceA + "/status",
                Map.of("status", "DISABLED"));
        assertThat(disabled.path("status").asText()).isEqualTo("DISABLED");
        assertThat(disabled.path("version").asLong()).isEqualTo(2L);
    }
    private void expectInvalid(String token, Map<String, Object> body) throws Exception {
        ResultActions result = mvc.perform(post("/api/v1/geofences").header("satoken", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
        result.andExpect(status().isBadRequest());
        JsonNode problem = json.readTree(result.andReturn().getResponse().getContentAsByteArray());
        assertThat(problem.path("code").asText()).isEqualTo("INVALID_GEOFENCE_GEOMETRY");
    }

    private Map<String, Object> circle(String name, double lng, double lat, Double radius) {
        Map<String, Object> body = base(name);
        body.put("fenceType", "CIRCLE");
        body.put("centerLng", lng);
        body.put("centerLat", lat);
        body.put("radiusM", radius);
        return body;
    }

    private Map<String, Object> polygon(String name, List<Map<String, Object>> vertices) {
        Map<String, Object> body = base(name);
        body.put("fenceType", "POLYGON");
        body.put("vertices", vertices);
        return body;
    }

    private Map<String, Object> base(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fenceName", name + "-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("enterEnabled", true);
        body.put("leaveEnabled", true);
        body.put("vehicleIds", List.of());
        return body;
    }

    private Map<String, Object> vertex(double lng, double lat) {
        Map<String, Object> vertex = new LinkedHashMap<>();
        vertex.put("lng", lng);
        vertex.put("lat", lat);
        return vertex;
    }

    private List<Map<String, Object>> ring(int vertices) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < vertices; i++) {
            double angle = 2 * Math.PI * i / vertices;
            points.add(vertex(116.4 + 0.01 * Math.cos(angle), 39.9 + 0.01 * Math.sin(angle)));
        }
        return points;
    }

    private JsonNode create(String token, Map<String, Object> body) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/geofences").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
    }

    private void expectForbidden(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvcExpect(request, 403);
    }

    private void mockMvcExpect(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               int status) throws Exception {
        mvc.perform(request).andExpect(status().is(status));
    }

    private JsonNode getJson(String token, String path) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1" + path).header("satoken", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    private JsonNode putJson(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(put("/api/v1" + path).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private long createResource(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1" + path).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("id").asLong();
    }
    private String login(String username, String password) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .path("token").asText();
    }
}