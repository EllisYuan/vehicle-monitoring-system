package com.vms.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vms.support.IntegrationContainers;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 05 前先用 04 落地司机档案与关联历史。覆盖范围：字段最小化、每车唯一当前关联、
 * 区间历史不倒填、车队长掩码与数据范围、停用司机历史引用、无关联时显示未关联。
 */
@SpringBootTest(properties = {
        "vms.bootstrap-admin.username=driver-admin",
        "vms.bootstrap-admin.password=Driver-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
})
@Import({IntegrationContainers.class, DriverFlowTest.TimeConfiguration.class})
@AutoConfigureMockMvc
class DriverFlowTest {

    private static final String ADMIN = "driver-admin";
    private static final String ADMIN_PASS = "Driver-Pass-2026";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired com.vms.support.MutableClock clock;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void archiveExposesOnlyTheFiveBusinessFieldsAndRejectsDuplicateDriverNo() throws Exception {
        String token = login(ADMIN, ADMIN_PASS);
        long fleet = createFleet(token);
        String driverNo = "D-" + suffix();

        long createdId = create(token, "/drivers", Map.of("driverNo", driverNo, "name", "张三",
                "phone", "13812345678", "fleetId", fleet));
        JsonNode created = read(token, "/drivers/" + createdId);
        assertThat(created.path("driverNo").asText()).isEqualTo(driverNo);
        assertThat(created.path("name").asText()).isEqualTo("张三");
        assertThat(created.path("phone").asText()).isEqualTo("13812345678");
        assertThat(created.path("fleetId").asLong()).isEqualTo(fleet);
        assertThat(created.path("status").asText()).isEqualTo("ENABLED");
        assertThat(created.findPath("idCardNo").isMissingNode()).isTrue();
        assertThat(created.findPath("licenseNo").isMissingNode()).isTrue();

        String duplicate = json.readTree(mvc.perform(post("/api/v1/drivers").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("driverNo", driverNo, "name", "李四",
                                "phone", "13900001111", "fleetId", fleet))))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsByteArray())
                .path("code").asText();
        assertThat(duplicate).isEqualTo("DRIVER_NO_EXISTS");
    }

    @Test
    void leaderSeesMaskedPhoneAndOnlyGrantedFleetsWhileMonitorAndAdminSeeFullPhone() throws Exception {
        String admin = login(ADMIN, ADMIN_PASS);
        String suffix = suffix();
        long granted = createFleet(admin, "A" + suffix);
        long other = createFleet(admin, "B" + suffix);
        long grantedDriver = createDriver(admin, granted, "D1");
        createDriver(admin, other, "D2");
        create(admin, "/users", Map.of("username", "leader-" + suffix, "password", "Leader-Pass-2026",
                "roleCode", "FLEET_LEADER", "fleetIds", List.of(granted)));
        create(admin, "/users", Map.of("username", "monitor-" + suffix, "password", "Monitor-Pass-2026",
                "roleCode", "MONITOR", "fleetIds", List.of()));
        String leader = login("leader-" + suffix, "Leader-Pass-2026");
        String monitor = login("monitor-" + suffix, "Monitor-Pass-2026");

        JsonNode adminView = read(admin, "/drivers/" + grantedDriver);
        JsonNode monitorView = read(monitor, "/drivers/" + grantedDriver);
        JsonNode leaderView = read(leader, "/drivers/" + grantedDriver);
        assertThat(adminView.path("phone").asText()).isEqualTo("13812345678");
        assertThat(monitorView.path("phone").asText()).isEqualTo("13812345678");
        assertThat(leaderView.path("phone").asText()).isEqualTo("138****5678");

        JsonNode leaderList = read(leader, "/drivers");
        assertThat(leaderList).allSatisfy(node -> assertThat(node.path("fleetId").asLong()).isEqualTo(granted));
        mvc.perform(get("/api/v1/drivers").param("fleetId", String.valueOf(other)).header("satoken", leader))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/drivers/" + grantedDriver).header("satoken", leader))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/drivers").header("satoken", leader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("driverNo", "D-X", "name", "王五",
                                "phone", "13700002222", "fleetId", granted))))
                .andExpect(status().isForbidden());
    }

    @Test
    void associationKeepsHalfOpenHistoryAndUnbindingShowsUnassociated() throws Exception {
        String token = login(ADMIN, ADMIN_PASS);
        long fleet = createFleet(token);
        long vehicle = createVehicle(token, fleet, "A");
        long first = createDriver(token, fleet, "D1");

        assertThat(read(token, "/vehicles/" + vehicle + "/driver").path("driverId").isNull()).isTrue();
        change(token, "/vehicles/" + vehicle + "/driver", Map.of("driverId", first));
        assertThat(read(token, "/vehicles/" + vehicle + "/driver").path("driverId").asLong()).isEqualTo(first);

        clock.advance(java.time.Duration.ofSeconds(1));
        long second = createDriver(token, fleet, "D2");
        change(token, "/vehicles/" + vehicle + "/driver", Map.of("driverId", second));
        JsonNode bindings = read(token, "/vehicles/" + vehicle + "/driver-bindings");
        assertThat(bindings.size()).isEqualTo(2);
        assertThat(bindings.get(0).path("effectiveTo").asText())
                .isEqualTo(bindings.get(1).path("effectiveFrom").asText());
        assertThat(bindings.get(1).path("effectiveTo").isNull()).isTrue();
        assertThat(bindings.get(0).path("driverId").asLong()).isEqualTo(first);
        assertThat(bindings.get(1).path("driverId").asLong()).isEqualTo(second);

        clock.advance(java.time.Duration.ofSeconds(1));
        change(token, "/vehicles/" + vehicle + "/driver", java.util.Collections.singletonMap("driverId", null));
        assertThat(read(token, "/vehicles/" + vehicle + "/driver").path("driverId").isNull()).isTrue();
        assertThat(read(token, "/vehicles/" + vehicle + "/driver-bindings").size()).isEqualTo(2);
    }

    @Test
    void concurrentAssociationLeavesExactlyOneCurrentDriverPerVehicle() throws Exception {
        String token = login(ADMIN, ADMIN_PASS);
        long fleet = createFleet(token);
        long vehicle = createVehicle(token, fleet, "A");
        long first = createDriver(token, fleet, "D1");
        long second = createDriver(token, fleet, "D2");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> associate(start, token, vehicle, first));
            var b = pool.submit(() -> associate(start, token, vehicle, second));
            start.countDown();
            int firstStatus = a.get(30, TimeUnit.SECONDS);
            int secondStatus = b.get(30, TimeUnit.SECONDS);
            assertThat(List.of(firstStatus, secondStatus)).allMatch(status -> status == 200 || status == 409);
            assertThat(List.of(firstStatus, secondStatus)).contains(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM vehicle_driver_binding WHERE current_vehicle_id = ?", Long.class, vehicle))
                .isEqualTo(1);
        assertThat(read(token, "/vehicles/" + vehicle + "/driver").path("driverId").asLong())
                .isIn(first, second);
    }

    @Test
    void disabledDriverStaysReferencedByHistoryAndCannotBeNewlyAssociated() throws Exception {
        String token = login(ADMIN, ADMIN_PASS);
        long fleet = createFleet(token);
        long vehicle = createVehicle(token, fleet, "A");
        long driver = createDriver(token, fleet, "D1");
        change(token, "/vehicles/" + vehicle + "/driver", Map.of("driverId", driver));
        clock.advance(java.time.Duration.ofSeconds(1));
        change(token, "/drivers/" + driver, Map.of("status", "DISABLED"));

        assertThat(read(token, "/drivers/" + driver).path("status").asText()).isEqualTo("DISABLED");
        JsonNode bindings = read(token, "/vehicles/" + vehicle + "/driver-bindings");
        assertThat(bindings.size()).isEqualTo(1);
        assertThat(bindings.get(0).path("driverId").asLong()).isEqualTo(driver);

        clock.advance(java.time.Duration.ofSeconds(1));
        long otherVehicle = createVehicle(token, fleet, "B");
        String rejected = rejectedChange(token, "/vehicles/" + otherVehicle + "/driver", Map.of("driverId", driver));
        assertThat(rejected).isEqualTo("DRIVER_DISABLED");
    }

    @Test
    void bindingIsRejectedWhenVehicleOrDriverIsOutOfScope() throws Exception {
        String admin = login(ADMIN, ADMIN_PASS);
        String suffix = suffix();
        long fleet = createFleet(admin, "A" + suffix);
        long vehicle = createVehicle(admin, fleet, "A" + suffix);
        long driver = createDriver(admin, fleet, "D1");
        create(admin, "/users", Map.of("username", "leader-" + suffix, "password", "Leader-Pass-2026",
                "roleCode", "FLEET_LEADER", "fleetIds", List.of(fleet)));
        String leader = login("leader-" + suffix, "Leader-Pass-2026");

        // 车队长可读授权车队的司机与车辆，但维护档案与关联属管理员职责
        mvc.perform(put("/api/v1/vehicles/" + vehicle + "/driver").header("satoken", leader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("driverId", driver))))
                .andExpect(status().isForbidden());
        assertThat(read(admin, "/vehicles/" + vehicle + "/driver").path("driverId").isNull()).isTrue();
    }

    @Test
    void associationChangesAndArchiveUpdatesAreAudited() throws Exception {
        String token = login(ADMIN, ADMIN_PASS);
        long fleet = createFleet(token);
        long vehicle = createVehicle(token, fleet, "A");
        long driver = createDriver(token, fleet, "D1");
        clock.advance(java.time.Duration.ofSeconds(1));
        change(token, "/vehicles/" + vehicle + "/driver", Map.of("driverId", driver));
        clock.advance(java.time.Duration.ofSeconds(1));
        change(token, "/drivers/" + driver, Map.of("name", "改名司机"));
        clock.advance(java.time.Duration.ofSeconds(1));
        change(token, "/vehicles/" + vehicle + "/driver", java.util.Collections.singletonMap("driverId", null));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE category = 'DRIVER' "
                + "AND action = 'ASSOCIATE' AND target = ?", Long.class, "vehicle:" + vehicle)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE category = 'DRIVER' "
                + "AND action = 'UPDATE' AND target = ?", Long.class, "driver:" + driver)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE category = 'DRIVER' "
                + "AND action = 'UNASSOCIATE' AND target = ?", Long.class, "vehicle:" + vehicle)).isEqualTo(1);
    }

    @Test
    void databaseRejectsTwoCurrentAssociationsAndDeletingReferencedArchives() throws Exception {
        String token = login(ADMIN, ADMIN_PASS);
        long fleet = createFleet(token);
        long firstVehicle = createVehicle(token, fleet, "A");
        long secondVehicle = createVehicle(token, fleet, "B");
        long firstDriver = createDriver(token, fleet, "D1");
        long secondDriver = createDriver(token, fleet, "D2");
        change(token, "/vehicles/" + firstVehicle + "/driver", Map.of("driverId", firstDriver));

        // 同一车辆第二条当前关联、同一司机第二辆当前车辆都必须被数据库拒绝
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO vehicle_driver_binding (driver_id, vehicle_id, effective_from)
                VALUES (?, ?, '2026-11-10 10:00:00')
                """, secondDriver, firstVehicle))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO vehicle_driver_binding (driver_id, vehicle_id, effective_from)
                VALUES (?, ?, '2026-11-10 10:00:00')
                """, firstDriver, secondVehicle))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("DELETE FROM driver WHERE id = ?", firstDriver))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private int associate(CountDownLatch start, String token, long vehicle, long driver) throws Exception {
        start.await();
        return mvc.perform(put("/api/v1/vehicles/" + vehicle + "/driver").header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("driverId", driver))))
                .andReturn().getResponse().getStatus();
    }

    private long createFleet(String token) throws Exception {
        return createFleet(token, suffix());
    }

    private long createFleet(String token, String code) throws Exception {
        return create(token, "/fleets", Map.of("fleetCode", code, "fleetName", "模拟车队"));
    }

    private long createVehicle(String token, long fleet, String prefix) throws Exception {
        return create(token, "/vehicles", Map.of("plateNo", prefix + suffix(), "fleetId", fleet));
    }

    private long createDriver(String token, long fleet, String prefix) throws Exception {
        return create(token, "/drivers", Map.of("driverNo", prefix + "-" + suffix(), "name", "模拟司机",
                "phone", "13812345678", "fleetId", fleet));
    }

    private JsonNode change(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(put("/api/v1" + path).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private String rejectedChange(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(put("/api/v1" + path).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsByteArray())
                .path("code").asText();
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String login(String username, String password) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray()).path("token").asText();
    }

    private long create(String token, String path, Object body) throws Exception {
        var response = mvc.perform(post("/api/v1" + path).header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray()).path("id").asLong();
    }

    private JsonNode read(String token, String path) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1" + path).header("satoken", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TimeConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.vms.support.MutableClock driverClock() {
            return new com.vms.support.MutableClock(java.time.Instant.parse("2026-11-09T02:00:00Z"),
                    java.time.ZoneId.of("Asia/Shanghai"));
        }
    }
}
