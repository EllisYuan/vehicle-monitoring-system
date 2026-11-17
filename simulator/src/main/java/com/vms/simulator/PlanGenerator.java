package com.vms.simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 22：把配置展开为规范化计划。生成只做编排：决定何时连接、发什么报文、何时断开，
 * 不判断超速、围栏、离线或报警结论——这些由平台在本轮真实运行中产生。
 * 所有随机量（路线抖动、方向、速度）都由 seed 派生，不使用 Math.random。
 */
public final class PlanGenerator {

    private static final int NORMAL_POINTS = 5;
    private static final int PARKING_POINTS = 3;
    private static final int OVERSPEED_POINTS = 5;
    private static final int GNSS_POINTS = 3;
    private static final int GEOFENCE_POINTS = 9;
    private static final int BACKFILL_ITEMS = 3;
    private static final int BACKFILL_ITEM_INTERVAL_SECONDS = 10;
    private static final long COMMAND_LOGOUT_MARGIN_MS = 500;
    private static final int RAW_SPEED_UNITS_PER_KPH = 10;

    private PlanGenerator() {
    }

    public static ScenarioPlan generate(ScenarioConfig config) {
        List<PlanEntry> entries = new ArrayList<>();
        for (int index = 0; index < config.vehicles().size(); index++) {
            entries.addAll(generateTerminal(config, config.vehicles().get(index), index));
        }
        return new ScenarioPlan(config.vehicles(), entries);
    }

    private static List<PlanEntry> generateTerminal(ScenarioConfig config, String terminal, int terminalIndex) {
        long start = (long) terminalIndex * config.vehicleStartStaggerMs();
        List<PlanEntry> entries = new ArrayList<>();
        List<ScenarioType> scenes = config.enabledScenes();
        ScenarioType initial = scenes.getFirst();
        entries.add(PlanEntry.control(terminal, initial, PlanEntry.CONNECT, start, 0x0100));
        long cursor = start;
        for (ScenarioType scene : scenes) {
            Block block = switch (scene) {
                case NORMAL -> normal(config, terminal, terminalIndex, scene, cursor);
                case PARKING -> parking(config, terminal, terminalIndex, scene, cursor);
                case OVERSPEED -> overspeed(config, terminal, terminalIndex, scene, cursor);
                case GNSS -> gnss(config, terminal, terminalIndex, scene, cursor);
                case GEOFENCE -> geofence(config, terminal, terminalIndex, scene, cursor);
                case OFFLINE -> offline(config, terminal, terminalIndex, scene, cursor);
                case BACKFILL -> backfill(config, terminal, terminalIndex, scene, cursor);
                case DUPLICATE -> duplicate(config, terminal, terminalIndex, scene, cursor);
                case ACK_LOSS -> ackLoss(config, terminal, terminalIndex, scene, cursor);
                case COMMANDS -> commands(config, terminal, terminalIndex, scene, cursor);
            };
            entries.addAll(block.entries());
            cursor = block.nextCursor();
        }
        return entries;
    }

    private static Block normal(ScenarioConfig config, String terminal, int terminalIndex,
                                ScenarioType scene, long start) {
        Random random = randomFor(config, terminal, scene);
        List<PlanEntry> entries = new ArrayList<>();
        for (int i = 0; i < NORMAL_POINTS; i++) {
            entries.add(PlanEntry.position(terminal, scene, start + i * (long) config.intervalMs(),
                    routeLng(config, i, random), routeLat(config, i, random),
                    speed(40, random), random.nextInt(360), true, 0));
        }
        return new Block(entries, start + NORMAL_POINTS * (long) config.intervalMs());
    }

    private static Block parking(ScenarioConfig config, String terminal, int terminalIndex,
                                 ScenarioType scene, long start) {
        Random random = randomFor(config, terminal, scene);
        double lng = routeLng(config, 0, random);
        double lat = routeLat(config, 0, random);
        int direction = random.nextInt(360);
        List<PlanEntry> entries = new ArrayList<>();
        for (int i = 0; i < PARKING_POINTS; i++) {
            entries.add(PlanEntry.position(terminal, scene, start + i * (long) config.intervalMs(),
                    lng, lat, 0, direction, true, 0));
        }
        return new Block(entries, start + PARKING_POINTS * (long) config.intervalMs());
    }

    private static Block overspeed(ScenarioConfig config, String terminal, int terminalIndex,
                                   ScenarioType scene, long start) {
        Random random = randomFor(config, terminal, scene);
        List<PlanEntry> entries = new ArrayList<>();
        for (int i = 0; i < OVERSPEED_POINTS; i++) {
            // 仅发送速度字段；是否构成超速由平台按规则判定。
            entries.add(PlanEntry.position(terminal, scene, start + i * (long) config.intervalMs(),
                    routeLng(config, i, random), routeLat(config, i, random),
                    (95 + random.nextInt(11)) * RAW_SPEED_UNITS_PER_KPH, random.nextInt(360), true, 0));
        }
        return new Block(entries, start + OVERSPEED_POINTS * (long) config.intervalMs());
    }

    private static Block gnss(ScenarioConfig config, String terminal, int terminalIndex,
                              ScenarioType scene, long start) {
        Random random = randomFor(config, terminal, scene);
        List<PlanEntry> entries = new ArrayList<>();
        for (int i = 0; i < GNSS_POINTS; i++) {
            // 诚实边界：仓库内没有 alarm bit 语义映射，只表达 status 定位无效位。
            entries.add(PlanEntry.position(terminal, scene, start + i * (long) config.intervalMs(),
                    routeLng(config, i, random), routeLat(config, i, random),
                    30 * RAW_SPEED_UNITS_PER_KPH, random.nextInt(360), false, 0));
        }
        return new Block(entries, start + GNSS_POINTS * (long) config.intervalMs());
    }

    private static Block geofence(ScenarioConfig config, String terminal, int terminalIndex,
                                  ScenarioType scene, long start) {
        Random random = randomFor(config, terminal, scene);
        List<PlanEntry> entries = new ArrayList<>();
        for (int i = 0; i < GEOFENCE_POINTS; i++) {
            // 经度按固定步长穿越围栏边界，纬度只做微小抖动，保证步长可核对。
            double lng = config.route().startLng() + i * config.geofenceStepLng();
            double lat = config.route().startLat() + (random.nextDouble() - 0.5) * 0.00008;
            entries.add(PlanEntry.position(terminal, scene, start + i * (long) config.intervalMs(),
                    lng, lat, 30 * RAW_SPEED_UNITS_PER_KPH, random.nextInt(360), true, 0));
        }
        return new Block(entries, start + GEOFENCE_POINTS * (long) config.intervalMs());
    }

    private static Block offline(ScenarioConfig config, String terminal, int terminalIndex,
                                 ScenarioType scene, long start) {
        Random random = randomFor(config, terminal, scene);
        int interval = config.intervalMs();
        long disconnect = start + 2L * interval;
        long reconnect = disconnect + config.offlineGapMs();
        List<PlanEntry> entries = new ArrayList<>();
        entries.add(PlanEntry.position(terminal, scene, start, routeLng(config, 0, random),
                routeLat(config, 0, random), 30 * RAW_SPEED_UNITS_PER_KPH, random.nextInt(360), true, 0));
        entries.add(PlanEntry.position(terminal, scene, start + interval, routeLng(config, 1, random),
                routeLat(config, 1, random), 30 * RAW_SPEED_UNITS_PER_KPH, random.nextInt(360), true, 0));
        entries.add(PlanEntry.control(terminal, scene, PlanEntry.DISCONNECT, disconnect, 0));
        entries.add(PlanEntry.control(terminal, scene, PlanEntry.CONNECT, reconnect, 0x0100));
        entries.add(PlanEntry.position(terminal, scene, reconnect + interval,
                routeLng(config, 2, random), routeLat(config, 2, random), 30 * RAW_SPEED_UNITS_PER_KPH,
                random.nextInt(360), true, 0));
        return new Block(entries, reconnect + 2L * interval);
    }

    private static Block backfill(ScenarioConfig config, String terminal, int terminalIndex,
                                  ScenarioType scene, long start) {
        Random random = randomFor(config, terminal, scene);
        List<PlanItem> items = new ArrayList<>();
        long firstOffset = -config.backfillSeconds();
        for (int i = 0; i < BACKFILL_ITEMS; i++) {
            items.add(new PlanItem(firstOffset + i * BACKFILL_ITEM_INTERVAL_SECONDS,
                    routeLng(config, i, random), routeLat(config, i, random),
                    40 * RAW_SPEED_UNITS_PER_KPH, random.nextInt(360), true));
        }
        return new Block(List.of(PlanEntry.batch(terminal, scene, start + config.intervalMs(), items)),
                start + 2L * config.intervalMs());
    }

    private static Block duplicate(ScenarioConfig config, String terminal, int terminalIndex,
                                   ScenarioType scene, long start) {
        Random random = randomFor(config, terminal, scene);
        double lng = routeLng(config, 0, random);
        double lat = routeLat(config, 0, random);
        int direction = random.nextInt(360);
        int rawSpeed = 40 * RAW_SPEED_UNITS_PER_KPH;
        PlanItem first = new PlanItem(0, lng, lat, rawSpeed, direction, true);
        PlanItem second = new PlanItem(0, lng, lat, rawSpeed, direction, true);
        PlanItem conflicting = new PlanItem(0, lng + config.route().stepLng(), lat, rawSpeed, direction, true);
        List<PlanItem> items = List.of(first, second, conflicting);
        return new Block(List.of(PlanEntry.batch(terminal, scene, start + config.intervalMs(), items)),
                start + 2L * config.intervalMs());
    }

    private static Block ackLoss(ScenarioConfig config, String terminal, int terminalIndex,
                                 ScenarioType scene, long start) {
        Random random = randomFor(config, terminal, scene);
        List<PlanEntry> entries = new ArrayList<>();
        entries.add(PlanEntry.position(terminal, scene, start, routeLng(config, 0, random),
                routeLat(config, 0, random), 40 * RAW_SPEED_UNITS_PER_KPH, random.nextInt(360), true,
                config.ackLossDropCount()));
        // 每次模拟 ACK 丢失都要等满 ack-timeout 才重发；后续计划按最坏等待顺延，
        // 避免实际发送挤进同一秒造成平台同秒冲突，掩盖本场景要验证的 ACK 重发语义。
        long retryDelay = config.ackLossDropCount() * (long) config.ackTimeoutMs();
        entries.add(PlanEntry.position(terminal, scene, start + retryDelay + config.intervalMs(),
                routeLng(config, 1, random), routeLat(config, 1, random),
                40 * RAW_SPEED_UNITS_PER_KPH, random.nextInt(360), true, 0));
        return new Block(entries, start + retryDelay + 2L * config.intervalMs());
    }

    private static Block commands(ScenarioConfig config, String terminal, int terminalIndex,
                                  ScenarioType scene, long start) {
        Random random = randomFor(config, terminal, scene);
        List<PlanEntry> entries = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            entries.add(PlanEntry.position(terminal, scene,
                    start + i * (long) config.commandBaseIntervalMs(),
                    routeLng(config, i, random), routeLat(config, i, random),
                    40 * RAW_SPEED_UNITS_PER_KPH, random.nextInt(360), true, 0));
        }
        long logout = start + config.commandWindowMs() + COMMAND_LOGOUT_MARGIN_MS;
        entries.add(PlanEntry.control(terminal, scene, PlanEntry.LOGOUT, logout, 0x0003));
        return new Block(entries, logout + config.intervalMs());
    }

    private static double routeLng(ScenarioConfig config, int step, Random random) {
        return config.route().startLng() + step * config.route().stepLng()
                + (random.nextDouble() - 0.5) * 0.00008;
    }

    private static double routeLat(ScenarioConfig config, int step, Random random) {
        return config.route().startLat() + step * config.route().stepLng() * 0.5
                + (random.nextDouble() - 0.5) * 0.00008;
    }

    private static int speed(int base, Random random) {
        // 协议速度字段单位为 0.1 km/h，平台按原始单位除以 10 得到 km/h。
        return (base + random.nextInt(6)) * RAW_SPEED_UNITS_PER_KPH;
    }

    private static Random randomFor(ScenarioConfig config, String terminal, ScenarioType scene) {
        long mixed = mix(config.seed(), terminal);
        mixed = mixed * 0x9E3779B97F4A7C15L + scene.ordinal();
        return new Random(mixed);
    }

    private static long mix(long seed, String terminal) {
        long hash = 0xCBF29CE484222325L ^ seed;
        for (int i = 0; i < terminal.length(); i++) {
            hash ^= terminal.charAt(i);
            hash *= 0x100000001B3L;
        }
        return hash;
    }

    private record Block(List<PlanEntry> entries, long nextCursor) {
    }
}


