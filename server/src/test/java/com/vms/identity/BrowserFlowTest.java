package com.vms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.vms.support.IntegrationContainers;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IntegrationContainers.class)
@EnabledIfSystemProperty(named = "vms.browser-tests", matches = "true")
class BrowserFlowTest {

    private static final String ADMIN_NAME = "browser-admin";
    private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void browserProperties(DynamicPropertyRegistry registry) {
        registry.add("vms.bootstrap-admin.username", () -> ADMIN_NAME);
        registry.add("vms.bootstrap-admin.password", () -> ADMIN_PASSWORD);
    }

    @Value("${local.server.port}")
    int serverPort;

    @Test
    void realBrowserExercisesLoginLayoutsAndSessionRecovery() throws Exception {
        Path web = Path.of("..", "web").toAbsolutePath().normalize();
        Path logs = Path.of("..", ".scratch", "vehicle-monitoring", "evidence", "logs")
                .toAbsolutePath().normalize();
        Files.createDirectories(logs);
        int webPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            webPort = socket.getLocalPort();
        }

        Process frontend = null;
        Process browser = null;
        try {
            ProcessBuilder frontendBuilder = pnpm(web, "dev --host 127.0.0.1 --strictPort --port " + webPort);
            frontendBuilder.environment().put("VMS_API_TARGET", "http://127.0.0.1:" + serverPort);
            frontendBuilder.redirectErrorStream(true).redirectOutput(logs.resolve("02-browser-vite.log").toFile());
            frontend = frontendBuilder.start();
            awaitFrontend(frontend, webPort);

            ProcessBuilder browserBuilder = pnpm(web, "test:e2e");
            browserBuilder.environment().put("VMS_E2E_USERNAME", ADMIN_NAME);
            browserBuilder.environment().put("VMS_E2E_PASSWORD", ADMIN_PASSWORD);
            browserBuilder.environment().put("VMS_E2E_WEB_PORT", String.valueOf(webPort));
            browserBuilder.redirectErrorStream(true).redirectOutput(logs.resolve("02-playwright.log").toFile());
            browser = browserBuilder.start();
            assertThat(browser.waitFor(240, TimeUnit.SECONDS))
                    .as("Playwright finishes within four minutes").isTrue();
            assertThat(browser.exitValue()).as("Playwright exit code; see 02-playwright.log").isZero();
        } finally {
            try {
                stop(browser);
            } finally {
                stop(frontend);
            }
        }
    }

    private ProcessBuilder pnpm(Path web, String command) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        return (windows
                ? new ProcessBuilder("cmd.exe", "/d", "/c", "pnpm " + command)
                : new ProcessBuilder("sh", "-c", "pnpm " + command)).directory(web.toFile());
    }

    private void awaitFrontend(Process frontend, int port) throws Exception {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(1)).build();
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        String lastFailure = "No response";
        while (System.nanoTime() < deadline && frontend.isAlive()) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port))
                        .timeout(Duration.ofSeconds(1)).build();
                int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status == 200) {
                    return;
                }
                lastFailure = "HTTP " + status;
            } catch (java.io.IOException e) {
                lastFailure = e.toString();
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Vite did not become ready: " + lastFailure
                + "; see 02-browser-vite.log");
    }

    private void stop(Process process) throws InterruptedException {
        if (process == null) {
            return;
        }
        var descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } finally {
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
