package com.vms.infrastructure.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vms.support.IntegrationContainers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * 17/AC03：会话绝对到期后旧 WS 订阅 5 秒内停止推送。
 * 为在合理时间内执行，本测试把 sa-token timeout 缩短为 4 秒，语义与生产 28800 秒绝对到期一致。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "vms.bootstrap-admin.username=ws-expiry-admin",
        "vms.bootstrap-admin.password=Expiry-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "vms.access.tcp-port=0",
        "sa-token.timeout=4"
})
@Import(IntegrationContainers.class)
@AutoConfigureMockMvc
class MonitorWebSocketExpiryTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @LocalServerPort int httpPort;

    @Test
    void subscriptionStopsWithinFiveSecondsAfterAbsoluteSessionExpiry() throws Exception {
        long loginAt = System.nanoTime();
        String token = json.readTree(mvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of(
                                        "username", "ws-expiry-admin", "password", "Expiry-Pass-2026"))))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .path("token").asText();
        String ticket = json.readTree(mvc.perform(post("/api/v1/auth/ws-ticket").header("satoken", token))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .path("ticket").asText();
        WsClient ws = new WsClient();
        try (ws) {
            ws.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(URI.create("ws://127.0.0.1:" + httpPort + "/ws/monitor?ticket=" + ticket), ws)
                    .get(5, TimeUnit.SECONDS);
            assertThat(ws.open.await(5, TimeUnit.SECONDS)).isTrue();
            Close close = ws.closed.get(10, TimeUnit.SECONDS);
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - loginAt).toMillis();
            // 绝对到期点约为登录后 4 秒；复权周期 1 秒，要求到期后 5 秒内停止。
            assertThat(elapsedMillis).isBetween(3000L, 9000L);
            assertThat(close.code()).isEqualTo(1008);
        }
    }

    private record Close(int code, String reason) { }

    private static final class WsClient implements WebSocket.Listener, AutoCloseable {
        final CountDownLatch open = new CountDownLatch(1);
        final CompletableFuture<Close> closed = new CompletableFuture<>();
        volatile WebSocket socket;

        @Override public void onOpen(WebSocket ws) {
            socket = ws;
            open.countDown();
            ws.request(1);
        }

        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            ws.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            closed.complete(new Close(code, reason));
            return null;
        }

        @Override public void onError(WebSocket ws, Throwable error) { closed.completeExceptionally(error); }

        @Override public void close() {
            if (socket != null) socket.abort();
        }
    }
}