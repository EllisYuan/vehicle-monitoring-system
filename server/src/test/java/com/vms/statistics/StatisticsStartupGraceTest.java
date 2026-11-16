package com.vms.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.vms.support.IntegrationContainers;
import com.vms.support.MutableClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 19：启动宽限内的车辆单列为“启动待确认”，宽限结束后才按已提交的 last_seen_at 归为离线。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "vms.bootstrap-admin.username=stats-grace-admin",
        "vms.bootstrap-admin.password=Stats-Grace-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0",
        "vms.statistics.startup-grace-seconds=180"
})
@Import({IntegrationContainers.class, StatisticsStartupGraceTest.TimeConfiguration.class})
class StatisticsStartupGraceTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @LocalServerPort int httpPort;

    @Test
    void startupGraceSeparatesPendingConfirmationFromOffline() throws Exception {
        String token = login();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long fleet = create(token, "/fleets", Map.of("fleetCode", "G" + suffix, "fleetName", "启动宽限" + suffix));
        long vehicle = create(token, "/vehicles", Map.of("plateNo", "宽限" + suffix, "fleetId", fleet));
        long terminal = create(token, "/terminals",
                Map.of("terminalNo", "0199000" + String.format("%013d", 1)));
        assertThat(status("PUT", "/vehicles/" + vehicle + "/terminal", token,
                Map.of("terminalId", terminal))).isEqualTo(200);
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        jdbc.update("UPDATE terminal SET ever_authenticated=TRUE,last_seen_at=? WHERE id=?",
                now.minusSeconds(600), terminal);

        JsonNode before = get(token, "/statistics/overview?fleetId=" + fleet).path("vehicles");
        assertThat(before.path("total").asLong()).isEqualTo(1);
        assertThat(before.path("startupPending").asLong())
                .as("启动 180 秒内不把尚未重连的终端判为离线").isEqualTo(1);
        assertThat(before.path("offline").asLong()).isZero();

        clock.advance(Duration.ofSeconds(181));

        JsonNode after = get(token, "/statistics/overview?fleetId=" + fleet).path("vehicles");
        assertThat(after.path("startupPending").asLong()).isZero();
        assertThat(after.path("offline").asLong())
                .as("宽限结束后只依据已提交的 last_seen_at 判离线").isEqualTo(1);
    }

    private String login() throws Exception {
        HttpCall call = call("POST", "/auth/login", Map.of("username", "stats-grace-admin",
                "password", "Stats-Grace-Pass-2026"), null);
        assertThat(call.status()).as(call.raw()).isEqualTo(200);
        return call.body().path("token").asText();
    }

    private long create(String token, String path, Object body) throws Exception {
        HttpCall call = call("POST", path, body, token);
        assertThat(call.status()).as("创建 %s 失败：%s", path, call.raw()).isEqualTo(201);
        return call.body().path("id").asLong();
    }

    private JsonNode get(String token, String path) throws Exception {
        HttpCall call = call("GET", path, null, token);
        assertThat(call.status()).as("GET %s 失败：%s", path, call.raw()).isEqualTo(200);
        return call.body();
    }

    private int status(String method, String path, String token, Object body) throws Exception {
        return call(method, path, body, token).status();
    }

    private HttpCall call(String method, String path, Object body, String token) throws Exception {
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
