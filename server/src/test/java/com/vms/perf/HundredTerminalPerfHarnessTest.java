package com.vms.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.vms.access.TerminalAccessPort;import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 24：100 终端 × 10 秒 × 30 分钟性能验收（AC32 / US89）。
 *
 * <p>默认不进入常规测试轮次。显式命令（先构建 simulator）：
 *
 * <pre>
 * mvnw.cmd -B -f &lt;root&gt;\pom.xml -DskipTests package
 * mvnw.cmd -B -f &lt;root&gt;\pom.xml -pl server -am \
 *     -Dtest=HundredTerminalPerfHarnessTest -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dvms.perf=true -Dvms.perf.round=1 test
 * </pre>
 *
 * <p>口径：
 * - 负载由 simulator 的 steady 场景经真实 TCP 注册/鉴权/位置上报进入服务端；
 * - 浏览器使用真实登录与真实 /ws/monitor 订阅，不轮询接口取位置；
 * - 延迟以浏览器渲染更新为准：offset = 客户端到达 − server_time，
 *   latency = (render − offset) − payload.receivedAt；该值把服务端→浏览器的单向网络与
 *   帧构建计入延迟，客户端→服务端单向被折进 offset，因此是保守估计；
 * - 唯一持久行以 track_point 的 COUNT(*) 与 COUNT(DISTINCT terminal_id, gps_time) 对账，
 *   18,000 仅为理论对照，实际以发送与成功 ACK 为准；
 * - 每次运行使用独立 Testcontainers MySQL/Redis 与 PERF- / 98 前缀档案，不改 migration。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"vms.access.tcp-port=0"})
@Import(PerfContainers.class)
@EnabledIfSystemProperty(named = "vms.perf", matches = "true")
class HundredTerminalPerfHarnessTest {

    static final int TERMINALS = 100;
    static final int INTERVAL_MS = 10_000;
    /**
     * 终端接入错峰：100 个 REGISTER/AUTH 事务同时打到 1 CPU 的 Testcontainers MySQL 会形成
     * 与 30 分钟稳态无关的惊群（实测超过 5 秒 ACK 超时并触发 REGISTER 重试，重试会覆盖
     * auth_secret_hash 导致 AUTH 被拒）。错峰 200ms/终端（总 19.8 秒）只改变接入阶段，
     * 稳态仍为 100 终端 × 10 秒 × 30 分钟。
     */
    static final int START_STAGGER_MS = 200;
    static final long DURATION_MS = 1_800_000L;
    static final int POINTS_PER_TERMINAL = (int) (DURATION_MS / INTERVAL_MS);
    static final long EXPECTED_POINTS = (long) TERMINALS * POINTS_PER_TERMINAL;
    static final long DISK_FLOOR_BYTES = 18L * 1024 * 1024 * 1024;
    /**
     * 接入阶段口径：scenario_start 起 60 秒。100 终端按 200ms 错峰接入（总 19.8 秒），
     * 首波位置点在 outbox 中排队；该阶段与稳态 30 分钟分开统计，避免把接入期排队
     * 计入 AC4 稳态 p95（上次 smoke 实测接入期 outbox 等待最高 10.6 秒）。
     */
    static final long PHASE_WARMUP_MS = 60_000L;
    static final long LOW_MEMORY_WARN_BYTES = 1_200_000_000L;
    static final long LOW_MEMORY_ABORT_BYTES = 800_000_000L;
    static final String FLEET_CODE = "PERF-FLEET";
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final String ADMIN_NAME = "perf-admin";
    private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void bootstrapAdmin(DynamicPropertyRegistry registry) {
        registry.add("vms.bootstrap-admin.username", () -> ADMIN_NAME);
        registry.add("vms.bootstrap-admin.password", () -> ADMIN_PASSWORD);
    }

    @Value("${local.server.port}")
    int serverPort;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TerminalAccessPort terminalAccess;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    javax.sql.DataSource dataSource;

    @Autowired
    @Qualifier("mysqlContainer")
    MySQLContainer<?> mysqlContainer;

    @Autowired
    @Qualifier("redisContainer")
    GenericContainer<?> redisContainer;

    @Autowired
    ObjectMapper json;

    @Test
    void hundredTerminalSteadyLoadIsMeasuredEndToEnd() throws Exception {
        String round = sanitize(System.getProperty("vms.perf.round", "run"));
        long durationMs = Long.getLong("vms.perf.duration-ms", DURATION_MS);
        boolean fullScale = durationMs == DURATION_MS;
        assertThat(durationMs % INTERVAL_MS)
                .as("duration-ms 必须为 interval-ms 的整数倍以生成等间隔稳态负载")
                .isZero();
        int pointsPerTerminal = (int) (durationMs / INTERVAL_MS);
        long expectedPoints = (long) TERMINALS * pointsPerTerminal;

        Path worktree = Path.of("..").toAbsolutePath().normalize();
        Path web = worktree.resolve("web");
        Path evidenceLogs = worktree.resolve(".scratch").resolve("vehicle-monitoring")
                .resolve("evidence").resolve("logs");
        Files.createDirectories(evidenceLogs);
        Path runDir = evidenceLogs.resolve("24-" + round + "-simulator");
        Path metricsPath = evidenceLogs.resolve("24-" + round + "-metrics.log");
        Path browserLog = evidenceLogs.resolve("24-" + round + "-browser.log");
        Path viteLog = evidenceLogs.resolve("24-" + round + "-vite.log");
        Path simulatorLog = evidenceLogs.resolve("24-" + round + "-simulator.log");
        Path framesPath = evidenceLogs.resolve("24-" + round + "-frames.json");
        Path screenshotsDir = evidenceLogs.resolve("24-" + round + "-screenshots");
        Path configPath = evidenceLogs.resolve("24-" + round + "-simulator-config.txt");

        try (Metrics metrics = new Metrics(metricsPath)) {
            metrics.put("run.round", round);
            metrics.put("run.scale", fullScale ? "full-30min" : "smoke-non-acceptance");
            metrics.put("run.terminals", TERMINALS);
            metrics.put("run.interval_ms", INTERVAL_MS);
            metrics.put("run.duration_ms", durationMs);
            metrics.put("run.expected_points_theoretical", expectedPoints);
            metrics.put("run.start_stagger_ms", START_STAGGER_MS);
            metrics.put("run.started_at", LocalDateTime.now(BUSINESS_ZONE).format(STAMP));
            metrics.put("run.host.disk_free_before_bytes", diskFree(worktree));

            prepareArchives();
            recordEnvironment(metrics, worktree, web);

            assertThat(filesExist(worktree.resolve("protocol").resolve("target").resolve("classes")))
                    .as("protocol 预编译产物存在（先执行 mvnw -DskipTests package）").isTrue();
            assertThat(filesExist(worktree.resolve("simulator").resolve("target").resolve("classes")))
                    .as("simulator 预编译产物存在（先执行 mvnw -DskipTests package）").isTrue();

            int webPort;
            try (ServerSocket socket = new ServerSocket(0)) {
                webPort = socket.getLocalPort();
            }

            Process frontend = null;
            Process browser = null;
            Process simulator = null;
            BrowserOutput browserOutput = null;
            long minDiskFree = diskFree(worktree);
            try {
                frontend = startFrontend(web, webPort, viteLog);
                awaitFrontend(frontend, webPort);

                List<String> terminals = terminalNos();
                String vehicles = String.join(",", terminals);
                Files.writeString(configPath, """
                        # 24 性能验收：steady 场景，100 终端，10 秒间隔
                        seed=20261124
                        vehicles=%s
                        host=127.0.0.1
                        port=%d
                        interval-ms=%d
                        vehicle-start-stagger-ms=%d
                        duration-ms=%d
                        scene.steady=true
                        """.formatted(vehicles, terminalAccess.port(), INTERVAL_MS, START_STAGGER_MS,
                        durationMs),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                metrics.put("simulator.config", configPath.getFileName());

                long rampMs = (long) START_STAGGER_MS * (TERMINALS - 1);
                long browserDurationMs = durationMs + rampMs + 120_000L;
                browser = startBrowser(web, browserLog, framesPath, screenshotsDir, webPort, browserDurationMs);
                browserOutput = new BrowserOutput(browser, browserLog);
                Thread reader = Thread.ofPlatform().name("perf-browser-reader").daemon(true)
                        .start(browserOutput);
                boolean ready = browserOutput.ready.await(240, TimeUnit.SECONDS);
                assertThat(ready)
                        .as("浏览器测量在 240 秒内完成登录、进入监控页并建立真实 WS 订阅；见 %s", browserLog)
                        .isTrue();
                assertThat(browser.isAlive()).as("浏览器测量进程在就绪后仍在运行").isTrue();
                long readyAtNanos = System.nanoTime();
                metrics.put("browser.ready", "true");
                browserOutput.environmentLines.forEach(line -> metrics.put("browser.env", line));

                simulator = startSimulator(worktree, configPath, runDir, simulatorLog);
                long simulatorDeadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(durationMs + rampMs + 300_000L);
                long nextGuardNanos = System.nanoTime();
                while (simulator.isAlive()) {
                    long remainingNanos = simulatorDeadline - System.nanoTime();
                    if (remainingNanos <= 0) {
                        throw new IllegalStateException("simulator 超出 duration-ms + 300 秒仍未结束");
                    }
                    simulator.waitFor(Math.min(TimeUnit.NANOSECONDS.toMillis(remainingNanos), 30_000L),
                            TimeUnit.MILLISECONDS);
                    if (System.nanoTime() >= nextGuardNanos) {
                        long free = checkRuntimeGuards(worktree, metrics);
                        if (free >= 0) {
                            minDiskFree = Math.min(minDiskFree, free);
                        }
                        nextGuardNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                    }
                }
                int simulatorExit = simulator.exitValue();
                metrics.put("simulator.exit_code", simulatorExit);

                long browserDeadlineNanos = readyAtNanos
                        + TimeUnit.MILLISECONDS.toNanos(browserDurationMs + 300_000L);
                long waitMillis = TimeUnit.NANOSECONDS.toMillis(browserDeadlineNanos - System.nanoTime());
                boolean browserFinished = browser.waitFor(Math.max(1_000L, waitMillis), TimeUnit.MILLISECONDS);
                assertThat(browserFinished)
                        .as("浏览器测量在预期窗口内结束；见 %s", browserLog)
                        .isTrue();
                int browserExit = browser.exitValue();
                metrics.put("browser.exit_code", browserExit);
                reader.join(TimeUnit.SECONDS.toMillis(30));
                metrics.put("browser.done_line", browserOutput.doneLine.get());

                assertThat(simulatorExit).as("simulator 全部终端 OK 时退出码为 0；见 %s", simulatorLog).isZero();
                assertThat(browserExit).as("浏览器测量脚本正常退出；见 %s", browserLog).isZero();

                metrics.put("run.host.disk_free_after_bytes", diskFree(worktree));
                metrics.put("run.host.disk_free_min_bytes", minDiskFree);

                analyseSimulator(metrics, runDir, expectedPoints, pointsPerTerminal);
                analyseDatabase(metrics, expectedPoints, pointsPerTerminal, durationMs);
                LatencyStats latency = analyseFrames(metrics, framesPath, expectedPoints, pointsPerTerminal);
                metrics.put("run.finished_at", LocalDateTime.now(BUSINESS_ZONE).format(STAMP));

                if (fullScale) {
                    boolean pass = latency.steadyP95Ms() <= 3_000.0;
                    metrics.put("ac4.criterion", "稳态段 p95 ≤ 3000ms；接入阶段单独如实报告，不参与 AC4 判定");
                    metrics.put("ac4.p95_pass", pass);
                    System.out.printf(Locale.ROOT,
                            "PERF_RESULT round=%s expected=%d persisted=%d "
                                    + "ramp_samples=%d ramp_p50=%.0fms ramp_p95=%.0fms ramp_max=%.0fms "
                                    + "steady_samples=%d steady_p50=%.0fms steady_p90=%.0fms "
                                    + "steady_p95=%.0fms steady_p99=%.0fms steady_max=%.0fms "
                                    + "overall_p50=%.0fms overall_p95=%.0fms overall_max=%.0fms pass=%s%n",
                            round, expectedPoints, latency.persistedRows(),
                            latency.rampSamples(), latency.rampP50Ms(), latency.rampP95Ms(),
                            latency.rampMaxMs(),
                            latency.steadySamples(), latency.steadyP50Ms(), latency.steadyP90Ms(),
                            latency.steadyP95Ms(), latency.steadyP99Ms(), latency.steadyMaxMs(),
                            latency.p50Ms(), latency.p95Ms(), latency.maxMs(), pass);
                    assertThat(latency.steadyP95Ms())
                            .as("AC4：接收至浏览器应用更新稳态 p95 必须 ≤ 3000ms（实际 %.0fms）",
                                    latency.steadyP95Ms())
                            .isLessThanOrEqualTo(3_000.0);
                } else {
                    metrics.put("ac4.p95_pass", "not-asserted-smoke");
                    System.out.printf(Locale.ROOT,
                            "PERF_SMOKE round=%s expected=%d persisted=%d ramp_samples=%d "
                                    + "ramp_p95=%.0fms steady_samples=%d steady_p95=%.0fms overall_p95=%.0fms%n",
                            round, expectedPoints, latency.persistedRows(), latency.rampSamples(),
                            latency.rampP95Ms(), latency.steadySamples(), latency.steadyP95Ms(),
                            latency.p95Ms());
                }
            } finally {
                stop(simulator);
                stop(browser);
                stop(frontend);
                if (browserOutput != null) {
                    browserOutput.close();
                }
            }
        }
    }

    // ------------------------------------------------------------------ 档案与断言

    private long fleetId;
    private long steadyFromGpsMs = -1;

    private void prepareArchives() {
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        LocalDateTime effectiveFrom = now.minusHours(2);
        jdbc.update("INSERT INTO fleet (fleet_code, fleet_name, status, created_at, updated_at)"
                + " VALUES (?, ?, 'ENABLED', ?, ?)", FLEET_CODE, "性能验收购车队", now, now);
        fleetId = jdbc.queryForObject("SELECT id FROM fleet WHERE fleet_code = ?", Long.class, FLEET_CODE);
        for (int index = 1; index <= TERMINALS; index++) {
            String plate = String.format(Locale.ROOT, "PERF-%04d", index);
            String terminalNo = String.format(Locale.ROOT, "98%018d", index);
            jdbc.update("INSERT INTO vehicle (plate_no, fleet_id, status, created_at, updated_at)"
                    + " VALUES (?, ?, 'ENABLED', ?, ?)", plate, fleetId, now, now);
            long vehicleId = jdbc.queryForObject("SELECT id FROM vehicle WHERE plate_no = ?",
                    Long.class, plate);
            jdbc.update("INSERT INTO terminal (terminal_no, status, ever_authenticated, created_at, updated_at)"
                    + " VALUES (?, 'ENABLED', FALSE, ?, ?)", terminalNo, now, now);
            long terminalId = jdbc.queryForObject("SELECT id FROM terminal WHERE terminal_no = ?",
                    Long.class, terminalNo);
            jdbc.update("INSERT INTO terminal_vehicle_binding (terminal_id, vehicle_id, effective_from)"
                    + " VALUES (?, ?, ?)", terminalId, vehicleId, effectiveFrom);
            jdbc.update("INSERT INTO vehicle_fleet_history (vehicle_id, fleet_id, effective_from)"
                    + " VALUES (?, ?, ?)", vehicleId, fleetId, effectiveFrom);
        }
        assertThat(count("SELECT COUNT(*) FROM fleet")).as("性能容器只允许 PERF-FLEET").isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT fleet_code FROM fleet", String.class)).isEqualTo(FLEET_CODE);
        assertThat(count("SELECT COUNT(*) FROM vehicle WHERE plate_no LIKE 'PERF-%'")).isEqualTo(TERMINALS);
        assertThat(count("SELECT COUNT(*) FROM vehicle WHERE plate_no NOT LIKE 'PERF-%'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM terminal WHERE terminal_no LIKE '98%'")).isEqualTo(TERMINALS);
        assertThat(count("SELECT COUNT(*) FROM terminal WHERE terminal_no NOT LIKE '98%'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM terminal_vehicle_binding")).isEqualTo(TERMINALS);
        assertThat(count("SELECT COUNT(*) FROM vehicle_fleet_history")).isEqualTo(TERMINALS);
    }

    private List<String> terminalNos() {
        return jdbc.queryForList("SELECT terminal_no FROM terminal WHERE terminal_no LIKE '98%'"
                + " ORDER BY terminal_no", String.class);
    }

    private long checkRuntimeGuards(Path worktree, Metrics metrics) throws IOException {
        FileStore store = Files.getFileStore(worktree);
        long free = store.getUsableSpace();
        if (free < DISK_FLOOR_BYTES) {
            throw new IllegalStateException("C: 可用空间低于 18 GB（" + free + " B），停止运行并保留现场");
        }
        // 上次满规模第 1 轮因宿主内存被看门狗 kill；这里每分钟记录一次可用内存，
        // 低于 1.2 GB 记警告，低于 0.8 GB 主动停止本轮并保留已写出的 metrics。
        long hostFree = hostFreeMemoryBytes();
        if (hostFree >= 0) {
            String stamp = LocalDateTime.now(BUSINESS_ZONE)
                    .format(DateTimeFormatter.ofPattern("HHmmss"));
            metrics.put("runtime.memory.free_bytes_at_" + stamp, hostFree);
            if (hostFree < LOW_MEMORY_WARN_BYTES) {
                metrics.put("runtime.memory.warning_at_" + stamp, hostFree);
                System.out.println("PERF_MEMORY_WARNING free_bytes=" + hostFree + " at=" + stamp);
            }
            if (hostFree < LOW_MEMORY_ABORT_BYTES) {
                throw new IllegalStateException("宿主可用内存低于 0.8 GB（" + hostFree
                        + " B），主动停止本轮并保留证据");
            }
        }
        long foreign = count("""
                SELECT COUNT(*) FROM track_point t
                JOIN vehicle v ON v.id = t.vehicle_id
                JOIN terminal tm ON tm.id = t.terminal_id
                WHERE v.plate_no NOT LIKE 'PERF-%' OR tm.terminal_no NOT LIKE '98%'
                """);
        if (foreign != 0) {
            throw new IllegalStateException("运行期出现非 PERF-/98 前缀轨迹行：" + foreign);
        }
        long foreignArchives = count("SELECT COUNT(*) FROM vehicle WHERE plate_no NOT LIKE 'PERF-%'")
                + count("SELECT COUNT(*) FROM terminal WHERE terminal_no NOT LIKE '98%'");
        if (foreignArchives != 0) {
            throw new IllegalStateException("运行期出现非性能验收档案：" + foreignArchives);
        }
        return free;
    }

    private static long hostFreeMemoryBytes() {
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            return sun.getFreeMemorySize();
        }
        return -1;
    }

    // ------------------------------------------------------------------ 环境记录

    private void recordEnvironment(Metrics metrics, Path worktree, Path web) throws IOException {
        metrics.put("env.os", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")");
        metrics.put("env.cpu.logical", Runtime.getRuntime().availableProcessors());
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            metrics.put("env.memory.total_bytes", sun.getTotalMemorySize());
            metrics.put("env.memory.free_bytes", sun.getFreeMemorySize());
        }
        metrics.put("env.jvm", System.getProperty("java.version") + " / "
                + System.getProperty("java.vendor") + " / " + System.getProperty("java.home"));
        metrics.put("env.jvm.args", ManagementFactory.getRuntimeMXBean().getInputArguments());
        metrics.put("env.spring_boot", SpringBootVersion.getVersion());
        metrics.put("env.timezone", ZoneId.systemDefault().getId());
        metrics.put("env.docker.server", runCommand(worktree, "docker", "version", "--format", "{{.Server.Version}}"));
        metrics.put("env.docker.vm", runCommand(worktree, "docker", "info", "--format",
                "NCPU={{.NCPU}} MemTotal={{.MemTotal}}"));
        metrics.put("env.docker.running_containers", runCommand(worktree, "docker", "ps", "--format",
                "{{.Names}}:{{.Image}}"));
        metrics.put("env.mysql.container_image", mysqlContainer.getDockerImageName());
        metrics.put("env.mysql.container_id", shortId(mysqlContainer.getContainerId()));
        metrics.put("env.mysql.container_limits", "memory=1536MiB nanoCpus=2.0，专用于本 harness");
        metrics.put("env.mysql.container_command",
                "--skip-log-bin --innodb-buffer-pool-size=768M --innodb-flush-log-at-trx-commit=2 "
                        + "--innodb-log-buffer-size=64M --innodb-doublewrite=OFF --max-connections=100 "
                        + "--performance-schema=OFF");
        metrics.put("env.redis.container_image", redisContainer.getDockerImageName());
        metrics.put("env.redis.container_id", shortId(redisContainer.getContainerId()));
        metrics.put("env.redis.container_limits", "memory=256MiB nanoCpus=0.5，专用于本 harness");
        metrics.put("env.mysql.version", jdbc.queryForObject("SELECT VERSION()", String.class));
        for (String variable : List.of("innodb_buffer_pool_size", "max_connections",
                "innodb_flush_log_at_trx_commit", "innodb_doublewrite", "transaction_isolation",
                "character_set_server", "collation_server", "innodb_log_file_size", "sync_binlog",
                "max_allowed_packet", "time_zone", "sql_mode")) {
            try {
                metrics.put("env.mysql." + variable,
                        jdbc.queryForObject("SELECT @@GLOBAL." + variable, String.class));
            } catch (RuntimeException e) {
                metrics.put("env.mysql." + variable, "unavailable: " + e.getMessage());
            }
        }
        try {
            Properties info = redis.execute((RedisCallback<Properties>) connection ->
                    connection.serverCommands().info("server"));
            metrics.put("env.redis.version", info == null ? "unavailable" : info.getProperty("redis_version"));
        } catch (RuntimeException e) {
            metrics.put("env.redis.version", "unavailable: " + e.getMessage());
        }
        if (dataSource instanceof HikariDataSource hikari) {
            metrics.put("env.jdbc.pool", "HikariDataSource max=" + hikari.getMaximumPoolSize()
                    + " minIdle=" + hikari.getMinimumIdle() + " jdbcUrl=" + hikari.getJdbcUrl());
        } else {
            metrics.put("env.jdbc.pool", dataSource.getClass().getName());
        }
        metrics.put("env.server.port", serverPort);
        metrics.put("env.server.tcp_port", terminalAccess.port());
        metrics.put("env.network",
                "simulator、浏览器与 server 均在本机；TCP 与 HTTP/WS 走 127.0.0.1 回环，"
                        + "位置链路不含外部网络；底图 fixture 请求被 Playwright 中止，不参与延迟口径");
        metrics.put("env.server.outbox_publish_interval_ms", "2000（vms.monitor.publish-interval-ms 默认值，未覆盖）");
        metrics.put("env.server.ws_revalidate_interval_ms", "1000（默认值，未覆盖）");
        metrics.put("env.web.package", packageVersions(web.resolve("package.json")));
        metrics.put("env.clock.method",
                "服务端与浏览器同宿主共享操作系统时钟；每帧另用 offset=clientArrival−server_time 校正，"
                        + "测量窗口内的 offset 分布见 latency.offset.*");
        metrics.put("env.measurement.method",
                "真实登录 + 真实 /ws/monitor 订阅；page.addInitScript 包装 WebSocket 记录 event_id、"
                        + "payload.receivedAt、server_time、客户端到达时刻与 requestAnimationFrame 回调时刻");
    }

    private static String packageVersions(Path packageJson) throws IOException {
        String text = Files.readString(packageJson, StandardCharsets.UTF_8);
        List<String> wanted = List.of("\"vite\"", "\"vue\"", "\"@playwright/test\"", "\"element-plus\"", "\"ol\"");
        List<String> found = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            for (String key : wanted) {
                if (trimmed.startsWith(key + ":")) {
                    found.add(key.replace("\"", "") + "=" + trimmed.substring(trimmed.indexOf(':') + 1)
                            .replace("\"", "").replace(",", "").trim());
                }
            }
        }
        return String.join(" ", found);
    }

    private static String runCommand(Path directory, String... command) {
        try {
            Process process = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "timeout";
            }
            return output.replace('\n', ' ');
        } catch (IOException e) {
            return "unavailable: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    // ------------------------------------------------------------------ 子进程

    private Process startFrontend(Path web, int webPort, Path log) throws IOException {
        // --configLoader runner：本次环境（Windows 受控文件夹访问）下 Vite 写 node_modules/.vite-temp
        // 会间歇性 ENOENT；runner 由 Node 直接加载同一 vite.config.ts，不写临时配置文件。
        // 仅影响测试环境的 dev server 启动方式，页面、代理与 WS 行为不变。
        ProcessBuilder builder = pnpm(web, "dev --host 127.0.0.1 --strictPort --port " + webPort
                + " --configLoader runner");
        builder.environment().put("VMS_API_TARGET", "http://127.0.0.1:" + serverPort);
        builder.environment().put("VITE_WMTS_ROAD_URL", "/__wmts-fixture/road?tk={key}");
        builder.environment().put("VITE_WMTS_LABEL_URL", "/__wmts-fixture/label?tk={key}");
        builder.environment().put("VITE_WMTS_KEY", "perf-fixture-key-not-real");
        builder.environment().put("VITE_WMTS_ATTRIBUTION", "WMTS failure fixture · 非真实天地图服务");
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        return builder.start();
    }

    private Process startBrowser(Path web, Path log, Path frames, Path screenshots, int webPort,
                                 long durationMs) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("node", "perf/hundred-terminal-latency.mjs")
                .directory(web.toFile());
        builder.environment().put("VMS_PERF_WEB_PORT", String.valueOf(webPort));
        builder.environment().put("VMS_PERF_USERNAME", ADMIN_NAME);
        builder.environment().put("VMS_PERF_PASSWORD", ADMIN_PASSWORD);
        builder.environment().put("VMS_PERF_DURATION_MS", String.valueOf(durationMs));
        builder.environment().put("VMS_PERF_FRAMES", frames.toAbsolutePath().toString());
        builder.environment().put("VMS_PERF_SCREENSHOTS_DIR", screenshots.toAbsolutePath().toString());
        builder.redirectErrorStream(true);
        // stdout 由 BrowserOutput 读取并同时写日志，避免丢失 PERF_READY/PERF_DONE。
        builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private Process startSimulator(Path worktree, Path config, Path outputDir, Path log) throws IOException {
        String classpath = worktree.resolve("protocol").resolve("target").resolve("classes")
                + File.pathSeparator
                + worktree.resolve("simulator").resolve("target").resolve("classes");
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath,
                "com.vms.simulator.ScenarioSimulator",
                "--config", config.toAbsolutePath().toString(),
                "--output-dir", outputDir.toAbsolutePath().toString());
        builder.directory(worktree.toFile());
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        return builder.start();
    }

    private ProcessBuilder pnpm(Path web, String command) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return (windows
                ? new ProcessBuilder("cmd.exe", "/d", "/c", "pnpm " + command)
                : new ProcessBuilder("sh", "-c", "pnpm " + command)).directory(web.toFile());
    }

    private void awaitFrontend(Process frontend, int port) throws Exception {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(1)).build();
        long deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos();
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
            } catch (IOException e) {
                lastFailure = e.toString();
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Vite 未在 60 秒内就绪：" + lastFailure + "；见 " + viteLogHint());
    }

    private static String viteLogHint() {
        return ".scratch/vehicle-monitoring/evidence/logs/24-*-vite.log";
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

    // ------------------------------------------------------------------ 对账

    private void analyseSimulator(Metrics metrics, Path runDir, long expectedPoints,
                                  int pointsPerTerminal) throws IOException {
        List<Path> sendLogs;
        try (var stream = Files.list(runDir)) {
            sendLogs = stream.filter(path -> path.getFileName().toString().endsWith("-send.tsv"))
                    .sorted(Comparator.comparingLong((Path path) -> path.toFile().lastModified()).reversed())
                    .toList();
        }
        assertThat(sendLogs).as("simulator 必须生成本轮的 send.tsv").isNotEmpty();
        Path sendLog = sendLogs.getFirst();
        String runId = sendLog.getFileName().toString().replace("-send.tsv", "");
        Path ackLog = runDir.resolve(runId + "-ack.tsv");
        Path summaryLog = runDir.resolve(runId + "-summary.tsv");
        Path manifest = runDir.resolve(runId + "-manifest.txt");
        assertThat(ackLog).exists();
        assertThat(summaryLog).exists();
        assertThat(manifest).exists();

        metrics.put("simulator.run_id", runId);
        String scenarioStartText = null;
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                metrics.put("manifest", line);
                if (line.startsWith("scenario_start=")) {
                    scenarioStartText = line.substring("scenario_start=".length()).trim();
                }
            }
        }

        List<String> sendLines = Files.readAllLines(sendLog, StandardCharsets.UTF_8);
        Map<String, Integer> sendHeader = header(sendLines.getFirst());
        LocalDateTime scenarioStart = scenarioStart(scenarioStartText);
        Map<Long, SendRow> sends = new LinkedHashMap<>();
        long positionAttempts = 0;
        long positionRetries = 0;
        long controlAttempts = 0;
        long connectControls = 0;
        List<Long> positionPlannedGps = new ArrayList<>();
        for (String line : sendLines.subList(1, sendLines.size())) {
            String[] columns = line.split("\t", -1);
            SendRow row = new SendRow(Long.parseLong(columns[sendHeader.get("send_id")]),
                    columns[sendHeader.get("terminal")],
                    columns[sendHeader.get("kind")],
                    columns[sendHeader.get("message_id")],
                    Integer.parseInt(columns[sendHeader.get("attempt")]),
                    Long.parseLong(columns[sendHeader.get("planned_offset_ms")]));
            sends.put(row.sendId(), row);
            if ("POSITION".equals(row.kind()) && "0200".equals(row.messageId())) {
                positionAttempts++;
                if (row.attempt() > 1) {
                    positionRetries++;
                } else {
                    positionPlannedGps.add(plannedGpsEpoch(scenarioStart, row.plannedOffsetMs()));
                }
            } else if (List.of("REGISTER", "AUTH", "HEARTBEAT").contains(row.kind())) {
                controlAttempts++;
            } else {
                connectControls++;
            }
        }

        steadyFromGpsMs = phaseBoundary(scenarioStart);
        long plannedRamp = positionPlannedGps.stream().filter(value -> value < steadyFromGpsMs).count();
        long plannedSteady = positionPlannedGps.size() - plannedRamp;
        metrics.put("phase.warmup_ms", PHASE_WARMUP_MS);
        metrics.put("phase.steady_from_gps_ms", steadyFromGpsMs);
        metrics.put("phase.scenario_start", scenarioStartText == null ? "unavailable" : scenarioStartText);
        metrics.put("simulator.position_planned_ramp", plannedRamp);
        metrics.put("simulator.position_planned_steady", plannedSteady);

        List<String> ackLines = Files.readAllLines(ackLog, StandardCharsets.UTF_8);
        Map<String, Integer> ackHeader = header(ackLines.getFirst());
        Map<String, Long> ackStatuses = new LinkedHashMap<>();
        long positionSuccessAcks = 0;
        long controlSuccessAcks = 0;
        long positionNonSuccessAcks = 0;
        for (String line : ackLines.subList(1, ackLines.size())) {
            String[] columns = line.split("\t", -1);
            String status = columns[ackHeader.get("status")];
            ackStatuses.merge(status, 1L, Long::sum);
            String sendIdText = columns[ackHeader.get("send_id")];
            if ("-".equals(sendIdText)) {
                continue;
            }
            SendRow row = sends.get(Long.parseLong(sendIdText));
            if (row == null) {
                continue;
            }
            boolean position = "POSITION".equals(row.kind()) && "0200".equals(row.messageId());
            boolean success = "ACK_SUCCESS".equals(status);
            if (position) {
                if (success) {
                    positionSuccessAcks++;
                } else {
                    positionNonSuccessAcks++;
                }
            } else if (success) {
                controlSuccessAcks++;
            }
        }

        List<String> summaryLines = Files.readAllLines(summaryLog, StandardCharsets.UTF_8);
        Map<String, Integer> summaryHeader = header(summaryLines.getFirst());
        Map<String, Long> terminalStatuses = new LinkedHashMap<>();
        long summaryRunSuccess = 0;
        for (String line : summaryLines.subList(1, summaryLines.size())) {
            String[] columns = line.split("\t", -1);
            String terminal = columns[summaryHeader.get("terminal")];
            String status = columns[summaryHeader.get("status")];
            if ("RUN".equals(terminal)) {
                summaryRunSuccess = "SUCCESS".equals(status) ? 1 : 0;
            } else {
                terminalStatuses.merge(status, 1L, Long::sum);
            }
        }

        metrics.put("simulator.send_rows", sendLines.size() - 1);
        metrics.put("simulator.ack_rows", ackLines.size() - 1);
        metrics.put("simulator.position_attempts", positionAttempts);
        metrics.put("simulator.position_retries", positionRetries);
        metrics.put("simulator.control_attempts", controlAttempts);
        metrics.put("simulator.connect_controls", connectControls);
        metrics.put("simulator.position_ack_success", positionSuccessAcks);
        metrics.put("simulator.position_ack_non_success", positionNonSuccessAcks);
        metrics.put("simulator.control_ack_success", controlSuccessAcks);
        for (Map.Entry<String, Long> entry : ackStatuses.entrySet()) {
            metrics.put("simulator.ack_status." + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Long> entry : terminalStatuses.entrySet()) {
            metrics.put("simulator.terminal_status." + entry.getKey(), entry.getValue());
        }
        metrics.put("simulator.summary_run_success", summaryRunSuccess);

        assertThat(summaryRunSuccess).as("simulator summary 必须标记 RUN SUCCESS").isEqualTo(1L);
        assertThat(terminalStatuses).as("simulator 终端状态只允许 OK").containsOnlyKeys("OK");
        assertThat(terminalStatuses.get("OK")).isEqualTo((long) TERMINALS);
        assertThat(positionAttempts)
                .as("位置发送尝试数必须等于理论点数（无 ACK 丢失配置）；多出的尝试必须逐条解释")
                .isEqualTo(expectedPoints);
        assertThat(positionRetries).as("位置发送不得出现重传").isZero();
        assertThat(positionSuccessAcks).as("位置成功 ACK 数必须等于发送数").isEqualTo(expectedPoints);
        assertThat(positionNonSuccessAcks).as("位置发送不得出现失败/超时/拒绝应答").isZero();
        assertThat(controlAttempts).as("每终端 REGISTER/AUTH/HEARTBEAT 各一次，不得重试")
                .isEqualTo((long) TERMINALS * 3);
        assertThat(connectControls).as("每终端仅一次 CONNECT 控制行").isEqualTo(TERMINALS);
        assertThat(controlSuccessAcks).as("每终端 REGISTER/AUTH/HEARTBEAT 各一次成功应答")
                .isEqualTo((long) TERMINALS * 3);
        assertThat(ackStatuses).as("ACK 记录只允许成功状态；任何超时/拒绝/丢弃都必须逐条解释")
                .containsOnlyKeys("ACK_SUCCESS");
        assertThat(ackStatuses.get("ACK_SUCCESS")).as("成功 ACK 总数 = 位置点数 + 控制报文数")
                .isEqualTo(expectedPoints + (long) TERMINALS * 3);
        assertThat(expectedPoints / TERMINALS).isEqualTo(pointsPerTerminal);
    }

    private static LocalDateTime scenarioStart(String scenarioStartText) {
        if (scenarioStartText == null || scenarioStartText.isBlank()) {
            throw new IllegalStateException("simulator manifest 缺少 scenario_start，无法划分接入阶段与稳态");
        }
        return LocalDateTime.parse(scenarioStartText);
    }

    /** 位置点的 gps_time 由 scenario_start + planned_offset_ms 截断到秒推导（与模拟器一致）。 */
    private static long plannedGpsEpoch(LocalDateTime scenarioStart, long plannedOffsetMs) {
        return scenarioStart.plus(plannedOffsetMs, ChronoUnit.MILLIS)
                .truncatedTo(ChronoUnit.SECONDS)
                .atZone(BUSINESS_ZONE).toInstant().toEpochMilli();
    }

    /** 接入阶段与稳态边界：scenario_start 所在秒 + 60 秒（按 track_point.gps_time 判定）。 */
    private static long phaseBoundary(LocalDateTime scenarioStart) {
        return scenarioStart.truncatedTo(ChronoUnit.SECONDS)
                .atZone(BUSINESS_ZONE).toInstant().toEpochMilli() + PHASE_WARMUP_MS;
    }

    private void analyseDatabase(Metrics metrics, long expectedPoints, int pointsPerTerminal,
                                 long durationMs) {
        long persisted = count("SELECT COUNT(*) FROM track_point");
        long distinct = count("SELECT COUNT(DISTINCT terminal_id, gps_time) FROM track_point");
        long vehicles = count("SELECT COUNT(DISTINCT vehicle_id) FROM track_point");
        long terminals = count("SELECT COUNT(DISTINCT terminal_id) FROM track_point");
        metrics.put("db.track_point.count", persisted);
        metrics.put("db.track_point.distinct_terminal_time", distinct);
        metrics.put("db.track_point.distinct_vehicles", vehicles);
        metrics.put("db.track_point.distinct_terminals", terminals);
        metrics.put("db.vehicle_last_position.count", count("SELECT COUNT(*) FROM vehicle_last_position"));
        metrics.put("db.alarm.count", count("SELECT COUNT(*) FROM alarm"));
        metrics.put("db.track_retention_rejection.count", count("SELECT COUNT(*) FROM track_retention_rejection"));
        metrics.put("db.telemetry_anomaly.count", count("SELECT COUNT(*) FROM telemetry_anomaly"));
        metrics.put("db.foreign_track_rows", count("""
                SELECT COUNT(*) FROM track_point t
                JOIN vehicle v ON v.id = t.vehicle_id
                JOIN terminal tm ON tm.id = t.terminal_id
                WHERE v.plate_no NOT LIKE 'PERF-%' OR tm.terminal_no NOT LIKE '98%'
                """));

        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT status, COUNT(*) AS c FROM notification_outbox GROUP BY status ORDER BY status")) {
            metrics.put("db.outbox.status." + row.get("status"), row.get("c"));
        }
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT reason, COUNT(*) AS c FROM telemetry_anomaly GROUP BY reason ORDER BY reason")) {
            metrics.put("db.anomaly." + row.get("reason"), row.get("c"));
        }

        Map<String, Long> perTerminal = new LinkedHashMap<>();
        jdbc.query("""
                SELECT t.terminal_no, COUNT(*) AS c FROM track_point tp
                JOIN terminal t ON t.id = tp.terminal_id
                GROUP BY t.terminal_no ORDER BY t.terminal_no
                """, rs -> {
            perTerminal.put(rs.getString(1), rs.getLong(2));
        });
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (Map.Entry<String, Long> entry : perTerminal.entrySet()) {
            metrics.put("db.terminal." + entry.getKey() + ".rows", entry.getValue());
            min = Math.min(min, entry.getValue());
            max = Math.max(max, entry.getValue());
        }
        metrics.put("db.terminal.rows_min", min == Long.MAX_VALUE ? 0 : min);
        metrics.put("db.terminal.rows_max", max == Long.MIN_VALUE ? 0 : max);

        Map<String, Object> span = jdbc.queryForMap(
                "SELECT MIN(gps_time) AS mn, MAX(gps_time) AS mx, MIN(received_at) AS rmn,"
                        + " MAX(received_at) AS rmx FROM track_point");
        LocalDateTime gpsMin = (LocalDateTime) span.get("mn");
        LocalDateTime gpsMax = (LocalDateTime) span.get("mx");
        LocalDateTime receivedMin = (LocalDateTime) span.get("rmn");
        LocalDateTime receivedMax = (LocalDateTime) span.get("rmx");
        long gpsSpanSeconds = Duration.between(gpsMin, gpsMax).toSeconds();
        long receivedSpanSeconds = Duration.between(receivedMin, receivedMax).toSeconds();
        metrics.put("db.track_point.gps_min", gpsMin);
        metrics.put("db.track_point.gps_max", gpsMax);
        metrics.put("db.track_point.gps_span_seconds", gpsSpanSeconds);
        metrics.put("db.track_point.received_min", receivedMin);
        metrics.put("db.track_point.received_max", receivedMax);
        metrics.put("db.track_point.received_span_seconds", receivedSpanSeconds);

        Map<String, Long> perTerminalGpsSpan = new LinkedHashMap<>();
        jdbc.query("""
                SELECT t.terminal_no, MIN(tp.gps_time) AS mn, MAX(tp.gps_time) AS mx
                FROM track_point tp JOIN terminal t ON t.id = tp.terminal_id
                GROUP BY t.terminal_no ORDER BY t.terminal_no
                """, rs -> {
            perTerminalGpsSpan.put(rs.getString(1),
                    Duration.between(rs.getObject(2, LocalDateTime.class),
                            rs.getObject(3, LocalDateTime.class)).toSeconds());
        });
        metrics.put("db.terminal.gps_span_min_seconds",
                perTerminalGpsSpan.values().stream().mapToLong(Long::longValue).min().orElse(0));
        metrics.put("db.terminal.gps_span_max_seconds",
                perTerminalGpsSpan.values().stream().mapToLong(Long::longValue).max().orElse(0));

        assertThat(persisted).as("唯一持久行数必须与成功 ACK 对账一致").isEqualTo(expectedPoints);
        assertThat(distinct).as("COUNT(DISTINCT terminal_id, gps_time) 必须等于总行数（唯一键幂等）")
                .isEqualTo(expectedPoints);
        assertThat(metrics.value("simulator.position_ack_success")).isEqualTo(String.valueOf(expectedPoints));
        assertThat(vehicles).as("100 辆车都应有持久位置").isEqualTo(TERMINALS);
        assertThat(terminals).as("100 个终端都应有持久位置").isEqualTo(TERMINALS);
        assertThat(perTerminal).hasSize(TERMINALS);
        assertThat(min).as("每终端持久点数必须为 %d", pointsPerTerminal).isEqualTo(pointsPerTerminal);
        assertThat(max).as("每终端持久点数必须为 %d", pointsPerTerminal).isEqualTo(pointsPerTerminal);
        assertThat(gpsSpanSeconds).as("整体 GPS 时间跨度应覆盖错峰接入 + 单终端时长")
                .isGreaterThanOrEqualTo(durationMs / 1000 - INTERVAL_MS / 1000);
        assertThat(perTerminalGpsSpan).hasSize(TERMINALS);
        assertThat(perTerminalGpsSpan.values()).allSatisfy(value ->
                assertThat(value).as("单终端首末 GPS 点跨度应为 duration-ms/1000 − interval-ms/1000")
                        .isEqualTo(durationMs / 1000 - INTERVAL_MS / 1000));
        assertThat(metrics.value("db.telemetry_anomaly.count"))
                .as("稳态运行不应产生 telemetry_anomaly；若有，必须逐条解释")
                .isEqualTo("0");
        assertThat(metrics.value("db.foreign_track_rows"))
                .as("不允许混入非 PERF- 车辆或非 98 前缀终端").isEqualTo("0");
        assertThat(metrics.value("db.outbox.status.DEAD")).as("outbox 不得出现 DEAD 事件").isNull();
    }

    private LatencyStats analyseFrames(Metrics metrics, Path framesPath, long expectedPoints,
                                       int pointsPerTerminal) throws IOException {
        assertThat(framesPath).as("浏览器必须输出原始帧文件").exists();
        metrics.put("browser.frames_file_bytes", Files.size(framesPath));
        JsonNode root = json.readTree(Files.readAllBytes(framesPath));
        JsonNode frames = root.path("frames");
        int socketCount = root.path("sockets").asInt();
        long closeEvents = 0;
        long errorEvents = 0;
        for (JsonNode event : root.path("events")) {
            String kind = event.path("kind").asText();
            if ("close".equals(kind)) {
                closeEvents++;
            } else if ("error".equals(kind)) {
                errorEvents++;
            }
        }
        metrics.put("browser.sockets", socketCount);
        metrics.put("browser.close_events", closeEvents);
        metrics.put("browser.error_events", errorEvents);
        metrics.put("browser.events", root.path("events").toString());
        metrics.put("browser.page_errors", root.path("pageErrors").size());
        metrics.put("browser.console_errors", root.path("consoleErrors").size());
        metrics.put("browser.vehicle_rows_at_end", root.path("vehicleRows").asInt());
        metrics.put("browser.visibility_at_end", root.path("visibility").asText());
        metrics.put("browser.stream_status_at_end", root.path("streamStatus").asText());

        List<Long> offsets = new ArrayList<>();
        List<Long> outboxWaits = new ArrayList<>();
        List<Long> transports = new ArrayList<>();
        List<Long> renders = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        List<Long> rampLatencies = new ArrayList<>();
        List<Long> steadyLatencies = new ArrayList<>();
        List<Long> rampOutboxWaits = new ArrayList<>();
        List<Long> steadyOutboxWaits = new ArrayList<>();
        List<Long> rampTransports = new ArrayList<>();
        List<Long> steadyTransports = new ArrayList<>();
        List<Long> rampRenders = new ArrayList<>();
        List<Long> steadyRenders = new ArrayList<>();
        Map<Long, Integer> perVehicle = new LinkedHashMap<>();
        long skipped = 0;
        for (JsonNode frame : frames) {
            long serverTime = frame.path("serverTime").asLong();
            long receivedAt = frame.path("receivedAt").asLong();
            long gpsTime = frame.path("gpsTime").asLong();
            long clientArrival = frame.path("clientArrival").asLong();
            long render = frame.path("render").asLong();
            if (serverTime <= 0 || receivedAt <= 0 || gpsTime <= 0 || clientArrival <= 0 || render <= 0) {
                skipped++;
                continue;
            }
            long offset = clientArrival - serverTime;
            long latency = (render - offset) - receivedAt;
            long outboxWait = serverTime - receivedAt;
            long transport = clientArrival - serverTime;
            long renderDelay = render - clientArrival;
            offsets.add(offset);
            outboxWaits.add(outboxWait);
            transports.add(transport);
            renders.add(renderDelay);
            latencies.add(latency);
            if (gpsTime < steadyFromGpsMs) {
                rampLatencies.add(latency);
                rampOutboxWaits.add(outboxWait);
                rampTransports.add(transport);
                rampRenders.add(renderDelay);
            } else {
                steadyLatencies.add(latency);
                steadyOutboxWaits.add(outboxWait);
                steadyTransports.add(transport);
                steadyRenders.add(renderDelay);
            }
            perVehicle.merge(frame.path("vehicleId").asLong(), 1, Integer::sum);
        }
        metrics.put("browser.frames", frames.size());
        metrics.put("browser.frames_ramp", rampLatencies.size());
        metrics.put("browser.frames_steady", steadyLatencies.size());
        long plannedRamp = Long.parseLong(metrics.value("simulator.position_planned_ramp"));
        long plannedSteady = Long.parseLong(metrics.value("simulator.position_planned_steady"));
        long missingFrames = expectedPoints - frames.size();
        metrics.put("browser.frames_missing", missingFrames);
        metrics.put("browser.frames_missing_ramp", plannedRamp - rampLatencies.size());
        metrics.put("browser.frames_missing_steady", plannedSteady - steadyLatencies.size());
        metrics.put("browser.frames_missing_note",
                "outbox 按车合并推送：同一车辆旧 version 在派发前被新点覆盖时不会单独产生浏览器更新；"
                        + "缺失点仍已在 track_point 持久化并收到成功 ACK。接入期与稳态期分别计数");
        metrics.put("browser.frames_skipped_invalid", skipped);
        metrics.put("browser.vehicle_frame_min", perVehicle.values().stream().mapToInt(v -> v).min().orElse(0));
        metrics.put("browser.vehicle_frame_max", perVehicle.values().stream().mapToInt(v -> v).max().orElse(0));
        metrics.put("latency.samples", latencies.size());
        metrics.put("latency.definition",
                "latency=(render−offset)−payload.receivedAt，offset=clientArrival−server_time；"
                        + "服务端→浏览器单向网络与帧构建计入延迟，客户端→服务端单向折进 offset（保守估计）");
        metrics.put("latency.offset.p50_ms", percentile(offsets, 50));
        metrics.put("latency.offset.p95_ms", percentile(offsets, 95));
        metrics.put("latency.offset.min_ms", offsets.isEmpty() ? 0 : Collections.min(offsets));
        metrics.put("latency.offset.max_ms", offsets.isEmpty() ? 0 : Collections.max(offsets));
        metrics.put("latency.p50_ms", percentile(latencies, 50));
        metrics.put("latency.p90_ms", percentile(latencies, 90));
        metrics.put("latency.p95_ms", percentile(latencies, 95));
        metrics.put("latency.p99_ms", percentile(latencies, 99));
        metrics.put("latency.max_ms", latencies.isEmpty() ? 0 : Collections.max(latencies));
        metrics.put("segment.outbox_wait.p50_ms", percentile(outboxWaits, 50));
        metrics.put("segment.outbox_wait.p95_ms", percentile(outboxWaits, 95));
        metrics.put("segment.outbox_wait.max_ms", outboxWaits.isEmpty() ? 0 : Collections.max(outboxWaits));
        metrics.put("segment.transport.p50_ms", percentile(transports, 50));
        metrics.put("segment.transport.p95_ms", percentile(transports, 95));
        metrics.put("segment.transport.max_ms", transports.isEmpty() ? 0 : Collections.max(transports));
        metrics.put("segment.render.p50_ms", percentile(renders, 50));
        metrics.put("segment.render.p95_ms", percentile(renders, 95));
        metrics.put("segment.render.max_ms", renders.isEmpty() ? 0 : Collections.max(renders));
        double p95OutboxShare = latencyShare(outboxWaits, latencies, 95);
        metrics.put("segment.outbox_wait.share_of_p95", String.format(Locale.ROOT, "%.3f", p95OutboxShare));

        // 接入阶段（scenario_start 起 60 秒内的错峰接入与首波 outbox 排队）与稳态分段统计；
        // AC4 只以稳态段的 p95 判定，接入段的实际值同时如实报告。
        metrics.put("latency.ramp.samples", rampLatencies.size());
        metrics.put("latency.ramp.p50_ms", percentile(rampLatencies, 50));
        metrics.put("latency.ramp.p95_ms", percentile(rampLatencies, 95));
        metrics.put("latency.ramp.max_ms",
                rampLatencies.isEmpty() ? 0 : Collections.max(rampLatencies));
        metrics.put("segment.ramp.outbox_wait.p50_ms", percentile(rampOutboxWaits, 50));
        metrics.put("segment.ramp.outbox_wait.p95_ms", percentile(rampOutboxWaits, 95));
        metrics.put("segment.ramp.outbox_wait.max_ms",
                rampOutboxWaits.isEmpty() ? 0 : Collections.max(rampOutboxWaits));
        metrics.put("segment.ramp.transport.p95_ms", percentile(rampTransports, 95));
        metrics.put("segment.ramp.transport.max_ms",
                rampTransports.isEmpty() ? 0 : Collections.max(rampTransports));
        metrics.put("segment.ramp.render.p95_ms", percentile(rampRenders, 95));
        metrics.put("segment.ramp.render.max_ms",
                rampRenders.isEmpty() ? 0 : Collections.max(rampRenders));
        metrics.put("latency.steady.samples", steadyLatencies.size());
        metrics.put("latency.steady.p50_ms", percentile(steadyLatencies, 50));
        metrics.put("latency.steady.p90_ms", percentile(steadyLatencies, 90));
        metrics.put("latency.steady.p95_ms", percentile(steadyLatencies, 95));
        metrics.put("latency.steady.p99_ms", percentile(steadyLatencies, 99));
        metrics.put("latency.steady.max_ms",
                steadyLatencies.isEmpty() ? 0 : Collections.max(steadyLatencies));
        metrics.put("segment.steady.outbox_wait.p50_ms", percentile(steadyOutboxWaits, 50));
        metrics.put("segment.steady.outbox_wait.p95_ms", percentile(steadyOutboxWaits, 95));
        metrics.put("segment.steady.outbox_wait.max_ms",
                steadyOutboxWaits.isEmpty() ? 0 : Collections.max(steadyOutboxWaits));
        metrics.put("segment.steady.transport.p95_ms", percentile(steadyTransports, 95));
        metrics.put("segment.steady.transport.max_ms",
                steadyTransports.isEmpty() ? 0 : Collections.max(steadyTransports));
        metrics.put("segment.steady.render.p95_ms", percentile(steadyRenders, 95));
        metrics.put("segment.steady.render.max_ms",
                steadyRenders.isEmpty() ? 0 : Collections.max(steadyRenders));

        assertThat(latencies).as("必须捕获到浏览器 WS 位置帧").isNotEmpty();
        assertThat(steadyLatencies).as("稳态段必须捕获到浏览器 WS 位置帧").isNotEmpty();
        assertThat(skipped).as("所有帧都必须含可用的服务端/客户端时间戳").isZero();
        assertThat(socketCount).as("测量窗口内只允许一个 /ws/monitor 订阅（重连会丢帧且口径不纯）")
                .isEqualTo(1);
        assertThat(closeEvents).as("测量结束前订阅不得关闭").isZero();
        assertThat(errorEvents).as("测量结束前订阅不得报错").isZero();
        assertThat((long) frames.size())
                .as("浏览器更新帧数应覆盖绝大多数位置点；缺失只能由 outbox 合并推送解释")
                .isGreaterThanOrEqualTo(expectedPoints - (long) TERMINALS * 3);
        assertThat(perVehicle).hasSize(TERMINALS);
        assertThat(perVehicle.values()).allSatisfy(count ->
                assertThat(count.intValue()).as("单车辆合并推送不得丢失超过 3 个点")
                        .isGreaterThanOrEqualTo(pointsPerTerminal - 3));
        return new LatencyStats(latencies.size(), percentile(latencies, 50), percentile(latencies, 90),
                percentile(latencies, 95), percentile(latencies, 99),
                latencies.isEmpty() ? 0 : Collections.max(latencies),
                metrics.value("db.track_point.count") == null ? 0
                        : Long.parseLong(metrics.value("db.track_point.count")),
                steadyLatencies.size(), percentile(steadyLatencies, 50), percentile(steadyLatencies, 90),
                percentile(steadyLatencies, 95), percentile(steadyLatencies, 99),
                steadyLatencies.isEmpty() ? 0 : Collections.max(steadyLatencies),
                rampLatencies.size(), percentile(rampLatencies, 50), percentile(rampLatencies, 95),
                rampLatencies.isEmpty() ? 0 : Collections.max(rampLatencies));
    }

    private static double latencyShare(List<Long> segment, List<Long> total, double p) {
        double segmentP = percentile(segment, p);
        double totalP = percentile(total, p);
        return totalP == 0 ? 0 : segmentP / totalP;
    }

    /** 最近秩百分位：rank=ceil(p/100×n)，取排序后第 rank 个（1 起）。 */
    private static double percentile(List<Long> values, double p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        rank = Math.max(1, Math.min(sorted.size(), rank));
        return sorted.get(rank - 1);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static Map<String, Integer> header(String line) {
        String[] columns = line.split("\t", -1);
        Map<String, Integer> header = new LinkedHashMap<>();
        for (int index = 0; index < columns.length; index++) {
            header.put(columns[index], index);
        }
        return header;
    }

    private static boolean filesExist(Path directory) {
        return Files.isDirectory(directory);
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static String shortId(String id) {
        return id == null || id.length() <= 12 ? String.valueOf(id) : id.substring(0, 12);
    }

    private static long diskFree(Path path) throws IOException {
        return Files.getFileStore(path).getUsableSpace();
    }

    private record SendRow(long sendId, String terminal, String kind, String messageId, int attempt,
                           long plannedOffsetMs) { }

    private record LatencyStats(long samples, double p50Ms, double p90Ms, double p95Ms, double p99Ms,
                                double maxMs, long persistedRows,
                                long steadySamples, double steadyP50Ms, double steadyP90Ms,
                                double steadyP95Ms, double steadyP99Ms, double steadyMaxMs,
                                long rampSamples, double rampP50Ms, double rampP95Ms, double rampMaxMs) { }

    /** 逐行收集浏览器子进程 stdout，同时写日志并等待 READY。 */
    private static final class BrowserOutput implements Runnable, AutoCloseable {
        private final Process process;
        private final Path log;
        private final CountDownLatch ready = new CountDownLatch(1);
        private final List<String> environmentLines = Collections.synchronizedList(new ArrayList<>());
        private final AtomicReference<String> doneLine = new AtomicReference<>();
        private volatile BufferedWriter writer;

        BrowserOutput(Process process, Path log) {
            this.process = process;
            this.log = log;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                Files.createDirectories(log.getParent());
                writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                    if (line.startsWith("PERF_ENV")) {
                        environmentLines.add(line);
                    } else if (line.startsWith("PERF_READY")) {
                        ready.countDown();
                    } else if (line.startsWith("PERF_DONE") || line.startsWith("PERF_FAILED")) {
                        doneLine.set(line);
                    }
                }
            } catch (IOException ignored) {
                // 进程结束时流关闭属正常情况；退出码另行断言。
            }
        }

        @Override
        public void close() throws IOException {
            if (writer != null) {
                writer.close();
            }
        }
    }

    /** 逐行落盘并 flush 的 metrics 收集器，失败时也能保留已测数据。 */
    private static final class Metrics implements AutoCloseable {
        private final Path path;
        private final Map<String, String> values = new LinkedHashMap<>();
        private final BufferedWriter writer;

        Metrics(Path path) throws IOException {
            this.path = path;
            Files.createDirectories(path.getParent());
            this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        void put(String key, Object value) {
            String text = value == null ? "null" : String.valueOf(value);
            values.put(key, text);
            try {
                writer.write(key + "=" + text);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                throw new IllegalStateException("写入 metrics 失败：" + path, e);
            }
        }

        String value(String key) {
            return values.get(key);
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }
}
