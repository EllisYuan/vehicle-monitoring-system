package com.vms.simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 22：场景编排配置。键值均为显式白名单，未知键直接拒绝，避免拼写错误被静默忽略。
 * 本类不引入 Spring，CLI 可独立运行。
 */
public record ScenarioConfig(
        long seed,
        List<String> vehicles,
        String host,
        int port,
        int intervalMs,
        int vehicleStartStaggerMs,
        int ackTimeoutMs,
        int ackMaxRetries,
        int ackLossDropCount,
        long commandWindowMs,
        int commandBaseIntervalMs,
        long offlineGapMs,
        int backfillSeconds,
        long durationMs,
        Route route,
        double geofenceStepLng,
        List<ScenarioType> enabledScenes) {

    public static final int DEFAULT_PORT = 9000;
    public static final String DEFAULT_HOST = "127.0.0.1";

    public record Route(String crs, double startLng, double startLat, double stepLng) {
    }

    public ScenarioConfig {
        vehicles = List.copyOf(vehicles);
        enabledScenes = List.copyOf(enabledScenes);
    }

    public boolean sceneEnabled(ScenarioType type) {
        return enabledScenes.contains(type);
    }

    public static ScenarioConfig parse(String text) {
        Long seed = null;
        List<String> vehicles = null;
        String host = DEFAULT_HOST;
        Integer port = null;
        Integer intervalMs = null;
        Integer vehicleStartStaggerMs = null;
        Integer ackTimeoutMs = null;
        Integer ackMaxRetries = null;
        Integer ackLossDropCount = null;
        Long commandWindowMs = null;
        Integer commandBaseIntervalMs = null;
        Long offlineGapMs = null;
        Integer backfillSeconds = null;
        Long durationMs = null;
        String crs = null;
        Double startLng = null;
        Double startLat = null;
        Double stepLng = null;
        Double geofenceStepLng = null;
        java.util.EnumSet<ScenarioType> scenes = java.util.EnumSet.noneOf(ScenarioType.class);
        java.util.Set<String> seenKeys = new java.util.HashSet<>();

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("无法解析配置行：" + line);
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!seenKeys.add(key)) {
                throw new IllegalArgumentException("重复配置项：" + key);
            }
            switch (key) {
                case "seed" -> seed = parseLong(key, value);
                case "vehicles" -> vehicles = parseVehicles(value);
                case "host" -> host = value;
                case "port" -> port = parseInt(key, value);
                case "interval-ms" -> intervalMs = parseInt(key, value);
                case "vehicle-start-stagger-ms" -> vehicleStartStaggerMs = parseInt(key, value);
                case "ack-timeout-ms" -> ackTimeoutMs = parseInt(key, value);
                case "ack-max-retries" -> ackMaxRetries = parseInt(key, value);
                case "ack-loss-drop-count" -> ackLossDropCount = parseInt(key, value);
                case "command-window-ms" -> commandWindowMs = parseLong(key, value);
                case "command-base-interval-ms" -> commandBaseIntervalMs = parseInt(key, value);
                case "offline-gap-ms" -> offlineGapMs = parseLong(key, value);
                case "backfill-seconds" -> backfillSeconds = parseInt(key, value);
                case "duration-ms" -> durationMs = parseLong(key, value);
                case "route.crs" -> crs = value;
                case "route.start-lng" -> startLng = parseDouble(key, value);
                case "route.start-lat" -> startLat = parseDouble(key, value);
                case "route.step-lng" -> stepLng = parseDouble(key, value);
                case "geofence.step-lng" -> geofenceStepLng = parseDouble(key, value);
                default -> {
                    if (key.startsWith("scene.")) {
                        ScenarioType type = parseScene(key, value);
                        if (type != null) {
                            scenes.add(type);
                        }
                    } else {
                        throw new IllegalArgumentException("未知配置项：" + key);
                    }
                }
            }
        }

        if (seed == null) {
            throw new IllegalArgumentException("缺少必填配置项：seed");
        }
        if (vehicles == null) {
            throw new IllegalArgumentException("缺少必填配置项：vehicles");
        }
        int effectiveInterval = requirePositive("interval-ms", intervalMs == null ? 1000 : intervalMs);
        int effectiveStagger = vehicleStartStaggerMs == null ? 0 : vehicleStartStaggerMs;
        if (effectiveStagger < 0) {
            throw new IllegalArgumentException("vehicle-start-stagger-ms 不能为负：" + effectiveStagger);
        }
        int effectiveAckTimeout = requirePositive("ack-timeout-ms", ackTimeoutMs == null ? 5000 : ackTimeoutMs);
        int effectiveRetries = ackMaxRetries == null ? 3 : ackMaxRetries;
        if (effectiveRetries < 0 || effectiveRetries > 3) {
            throw new IllegalArgumentException("ack-max-retries 必须在 0 到 3 之间：" + effectiveRetries);
        }
        int effectiveDropCount = ackLossDropCount == null ? 1 : ackLossDropCount;
        if (effectiveDropCount < 0) {
            throw new IllegalArgumentException("ack-loss-drop-count 不能为负：" + effectiveDropCount);
        }
        long effectiveWindow = commandWindowMs == null ? 15000 : commandWindowMs;
        if (effectiveWindow <= 0) {
            throw new IllegalArgumentException("command-window-ms 必须大于 0：" + effectiveWindow);
        }
        int effectiveCommandBase = requirePositive("command-base-interval-ms",
                commandBaseIntervalMs == null ? effectiveInterval : commandBaseIntervalMs);
        long effectiveOfflineGap = offlineGapMs == null ? 3000 : offlineGapMs;
        if (effectiveOfflineGap <= 0) {
            throw new IllegalArgumentException("offline-gap-ms 必须大于 0：" + effectiveOfflineGap);
        }
        int effectiveBackfill = requirePositive("backfill-seconds",
                backfillSeconds == null ? 120 : backfillSeconds);
        long effectiveDuration = durationMs == null ? 0L : durationMs;
        if (scenes.contains(ScenarioType.STEADY)) {
            if (durationMs == null) {
                throw new IllegalArgumentException("启用 scene.steady 时必须提供 duration-ms");
            }
            if (effectiveDuration < effectiveInterval) {
                throw new IllegalArgumentException("duration-ms 不能小于 interval-ms："
                        + effectiveDuration + " < " + effectiveInterval);
            }
            if (effectiveDuration % effectiveInterval != 0) {
                throw new IllegalArgumentException("duration-ms 必须为 interval-ms 的整数倍："
                        + effectiveDuration + " / " + effectiveInterval);
            }
            long points = effectiveDuration / effectiveInterval;
            if (points > 100_000L) {
                throw new IllegalArgumentException("duration-ms 与 interval-ms 推导的单终端点数不能超过 100000："
                        + points);
            }
        } else if (durationMs != null) {
            throw new IllegalArgumentException("duration-ms 仅在启用 scene.steady 时可用");
        }
        String effectiveCrs = crs == null ? "WGS84" : crs;
        if (!"WGS84".equals(effectiveCrs)) {
            throw new IllegalArgumentException("route.crs 仅支持 WGS84：" + effectiveCrs);
        }
        int effectivePort = port == null ? DEFAULT_PORT : port;
        if (effectivePort < 1 || effectivePort > 65535) {
            throw new IllegalArgumentException("port 必须在 1 到 65535 之间：" + effectivePort);
        }
        if (scenes.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个启用场景（scene.<名称>=true）");
        }

        Route route = new Route(effectiveCrs,
                startLng == null ? 116.397 : startLng,
                startLat == null ? 39.908 : startLat,
                stepLng == null ? 0.0002 : stepLng);
        List<ScenarioType> ordered = new ArrayList<>();
        for (ScenarioType type : ScenarioType.values()) {
            if (scenes.contains(type)) {
                ordered.add(type);
            }
        }
        return new ScenarioConfig(seed, vehicles, host, effectivePort, effectiveInterval, effectiveStagger,
                effectiveAckTimeout, effectiveRetries, effectiveDropCount, effectiveWindow,
                effectiveCommandBase, effectiveOfflineGap, effectiveBackfill, effectiveDuration, route,
                geofenceStepLng == null ? 0.0015 : geofenceStepLng, ordered);
    }

    private static int requirePositive(String key, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " 必须大于 0：" + value);
        }
        return value;
    }

    private static List<String> parseVehicles(String value) {
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String terminal = part.trim();
            if (!terminal.matches("\\d{20}")) {
                throw new IllegalArgumentException("终端号必须为 20 位数字：" + terminal);
            }
            if (result.contains(terminal)) {
                throw new IllegalArgumentException("vehicles 中存在重复终端号：" + terminal);
            }
            result.add(terminal);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("vehicles 不能为空");
        }
        return List.copyOf(result);
    }

    private static ScenarioType parseScene(String key, String value) {
        String name = key.substring("scene.".length());
        ScenarioType type;
        try {
            type = ScenarioType.fromKey(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知配置项：" + key);
        }
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(key + " 只能为 true 或 false：" + value);
        }
        return "true".equalsIgnoreCase(value) ? type : null;
    }

    private static long parseLong(String key, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须为整数：" + value);
        }
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须为整数：" + value);
        }
    }

    private static double parseDouble(String key, String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须为数字：" + value);
        }
    }

    public String describe() {
        return String.format(Locale.ROOT,
                "seed=%d vehicles=%d host=%s port=%d interval-ms=%d duration-ms=%d ack-timeout-ms=%d "
                        + "ack-max-retries=%d scenes=%s",
                seed, vehicles.size(), host, port, intervalMs, durationMs, ackTimeoutMs, ackMaxRetries,
                enabledScenes);
    }
}

