package com.vms.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanGeneratorTest {

    private static final String BASE = """
            seed=20261116
            vehicles=01380022000000000001,01380022000000000002,01380022000000000003,01380022000000000004,01380022000000000005
            interval-ms=1000
            scene.normal=true
            scene.parking=true
            scene.overspeed=true
            scene.gnss=true
            scene.geofence=true
            scene.offline=true
            scene.backfill=true
            scene.duplicate=true
            scene.ack-loss=true
            scene.commands=true
            """;

    @Test
    void planSpeedsUseRawProtocolUnitsOfTenthsOfKph() {
        List<PlanEntry> overspeed = PlanGenerator.generate(ScenarioConfig.parse("""
                seed=7
                vehicles=01380022000000000001
                scene.overspeed=true
                """)).entries().stream().filter(entry -> "POSITION".equals(entry.kind())).toList();
        List<PlanEntry> normal = PlanGenerator.generate(ScenarioConfig.parse("""
                seed=7
                vehicles=01380022000000000001
                scene.normal=true
                """)).entries().stream().filter(entry -> "POSITION".equals(entry.kind())).toList();

        assertThat(overspeed).allSatisfy(entry -> assertThat(entry.speed()).isBetween(950, 1050));
        assertThat(normal).allSatisfy(entry -> assertThat(entry.speed()).isBetween(400, 450));
    }
    @Test
    void sameSeedProducesIdenticalNormalizedPlan() {
        ScenarioConfig config = ScenarioConfig.parse(BASE);

        ScenarioPlan first = PlanGenerator.generate(config);
        ScenarioPlan second = PlanGenerator.generate(ScenarioConfig.parse(BASE));

        assertThat(first.normalizedText()).isEqualTo(second.normalizedText());
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
    }

    @Test
    void differentSeedChangesRandomizedCoordinatesButKeepsPlanShape() {
        ScenarioConfig one = ScenarioConfig.parse(BASE.replace("seed=20261116", "seed=1"));
        ScenarioConfig two = ScenarioConfig.parse(BASE.replace("seed=20261116", "seed=2"));

        ScenarioPlan first = PlanGenerator.generate(one);
        ScenarioPlan second = PlanGenerator.generate(two);

        assertThat(first.entries()).hasSameSizeAs(second.entries());
        assertThat(first.fingerprint()).isNotEqualTo(second.fingerprint());
        assertThat(first.entries()).extracting(PlanEntry::kind)
                .containsExactlyElementsOf(second.entries().stream().map(PlanEntry::kind).toList());
    }

    @Test
    void singleSceneCanRunAloneWithoutTouchingOtherScenarios() {
        ScenarioConfig config = ScenarioConfig.parse("""
                seed=7
                vehicles=01380022000000000001
                scene.overspeed=true
                """);

        ScenarioPlan plan = PlanGenerator.generate(config);

        assertThat(plan.entries()).filteredOn(entry -> "POSITION".equals(entry.kind()))
                .hasSize(5)
                .allSatisfy(entry -> assertThat(entry.scene()).isEqualTo(ScenarioType.OVERSPEED));
        assertThat(plan.entries()).extracting(PlanEntry::scene)
                .doesNotContain(ScenarioType.GEOFENCE, ScenarioType.PARKING, ScenarioType.DUPLICATE);
    }

    @Test
    void offlineSceneDisconnectsWithoutLogoutAndReconnects() {
        ScenarioConfig config = ScenarioConfig.parse("""
                seed=7
                vehicles=01380022000000000001
                scene.offline=true
                """);

        ScenarioPlan plan = PlanGenerator.generate(config);

        assertThat(plan.entries()).extracting(PlanEntry::kind)
                .containsExactly("CONNECT", "POSITION", "POSITION", "DISCONNECT", "CONNECT", "POSITION");
        assertThat(plan.entries()).extracting(PlanEntry::offsetMs)
                .containsExactly(0L, 0L, 1000L, 2000L, 5000L, 6000L);
    }

    @Test
    void backfillItemsCarryNegativeGpsOffsets() {
        ScenarioConfig config = ScenarioConfig.parse("""
                seed=7
                vehicles=01380022000000000001
                scene.backfill=true
                """);

        PlanEntry batch = PlanGenerator.generate(config).entries().stream()
                .filter(entry -> entry.scene() == ScenarioType.BACKFILL && entry.messageId() == 0x0704)
                .findFirst().orElseThrow();

        assertThat(batch.items()).hasSize(3);
        assertThat(batch.items()).extracting(PlanItem::gpsOffsetSeconds)
                .containsExactly(-120L, -110L, -100L);
    }

    @Test
    void duplicateBatchHasSameSecondConflictOnThirdItem() {
        ScenarioConfig config = ScenarioConfig.parse("""
                seed=7
                vehicles=01380022000000000001
                scene.duplicate=true
                """);

        PlanEntry batch = PlanGenerator.generate(config).entries().stream()
                .filter(entry -> entry.scene() == ScenarioType.DUPLICATE && entry.messageId() == 0x0704)
                .findFirst().orElseThrow();

        assertThat(batch.items()).hasSize(3);
        assertThat(batch.items().get(0)).isEqualTo(batch.items().get(1));
        assertThat(batch.items().get(2).gpsOffsetSeconds()).isZero();
        assertThat(batch.items().get(2).lng()).isNotEqualTo(batch.items().get(0).lng());
    }

    @Test
    void ackLossSceneMarksFirstSendForAckDropOnly() {
        ScenarioConfig config = ScenarioConfig.parse("""
                seed=7
                vehicles=01380022000000000001
                ack-timeout-ms=1500
                ack-loss-drop-count=2
                scene.ack-loss=true
                """);

        List<PlanEntry> positions = PlanGenerator.generate(config).entries().stream()
                .filter(entry -> "POSITION".equals(entry.kind()))
                .toList();

        assertThat(positions).hasSize(2);
        assertThat(positions.getFirst().dropAcks()).isEqualTo(2);
        assertThat(positions.get(1).dropAcks()).isZero();
        // 两次模拟丢包各等满 1500ms，再留一个 interval，后续计划不能挤进同一秒。
        assertThat(positions.get(1).offsetMs()).isEqualTo(4000L);
    }

    @Test
    void geofenceSceneCrossesFenceWithNineRealtimePoints() {
        ScenarioConfig config = ScenarioConfig.parse("""
                seed=7
                vehicles=01380022000000000001
                route.start-lng=116.400
                route.start-lat=39.900
                route.step-lng=0.0002
                geofence.step-lng=0.0015
                scene.geofence=true
                """);

        List<PlanEntry> positions = PlanGenerator.generate(config).entries().stream()
                .filter(entry -> entry.scene() == ScenarioType.GEOFENCE && "POSITION".equals(entry.kind()))
                .toList();

        assertThat(positions).hasSize(9);
        assertThat(positions.get(1).lng() - positions.get(0).lng()).isCloseTo(0.0015,
                org.assertj.core.data.Offset.offset(0.0001));
        assertThat(positions).allSatisfy(entry -> assertThat(entry.fixValid()).isTrue());
    }

    @Test
    void commandsSceneSendsAtBaseIntervalThenKeepsListeningForWindow() {
        ScenarioConfig config = ScenarioConfig.parse("""
                seed=7
                vehicles=01380022000000000001
                command-base-interval-ms=2000
                command-window-ms=15000
                scene.commands=true
                """);

        List<PlanEntry> entries = PlanGenerator.generate(config).entries();
        List<PlanEntry> positions = entries.stream().filter(entry -> "POSITION".equals(entry.kind())).toList();

        assertThat(positions).extracting(PlanEntry::offsetMs).containsExactly(0L, 2000L, 4000L);
        assertThat(entries.getLast().kind()).isEqualTo("LOGOUT");
        assertThat(entries.getLast().offsetMs()).isEqualTo(15500L);
    }

    @Test
    void vehicleStartStaggerShiftsEachTerminalsTimelineByIndex() {
        String config = """
                seed=7
                vehicles=01380022000000000001,01380022000000000002,01380022000000000003
                interval-ms=1000
                vehicle-start-stagger-ms=400
                scene.normal=true
                """;

        ScenarioPlan plan = PlanGenerator.generate(ScenarioConfig.parse(config));

        assertThat(plan.entries()).filteredOn(entry -> "CONNECT".equals(entry.kind()))
                .extracting(PlanEntry::offsetMs).containsExactly(0L, 400L, 800L);
        for (int index = 0; index < plan.terminals().size(); index++) {
            String terminal = plan.terminals().get(index);
            long expectedStart = index * 400L;
            assertThat(plan.entries()).filteredOn(entry -> entry.terminalNo().equals(terminal)
                            && "POSITION".equals(entry.kind()))
                    .extracting(PlanEntry::offsetMs).startsWith(expectedStart);
        }
        assertThat(plan.normalizedText())
                .isEqualTo(PlanGenerator.generate(ScenarioConfig.parse(config)).normalizedText());
    }

    @Test
    void multipleVehiclesStartTogetherAndStaySerialPerTerminal() {
        ScenarioPlan plan = PlanGenerator.generate(ScenarioConfig.parse(BASE));

        assertThat(plan.terminals()).hasSize(5);
        for (String terminal : plan.terminals()) {
            List<Long> offsets = plan.entries().stream()
                    .filter(entry -> entry.terminalNo().equals(terminal))
                    .map(PlanEntry::offsetMs)
                    .toList();
            assertThat(offsets).isSorted();
        }
        long terminalsStartingAtZero = plan.entries().stream()
                .filter(entry -> entry.offsetMs() == 0L && "CONNECT".equals(entry.kind()))
                .count();
        assertThat(terminalsStartingAtZero).isEqualTo(5);
        assertThat(plan.entries().stream().map(PlanEntry::scene).collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of(ScenarioType.values()));
    }
}


