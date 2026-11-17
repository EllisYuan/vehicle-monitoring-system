package com.vms.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScenarioConfigTest {

    private static final String BASE = """
            seed=20261116
            vehicles=01380022000000000001,01380022000000000002
            scene.normal=true
            """;

    @Test
    void defaultsFollowEngineeringRetryPolicy() {
        ScenarioConfig config = ScenarioConfig.parse(BASE);

        assertThat(config.seed()).isEqualTo(20261116L);
        assertThat(config.ackTimeoutMs()).isEqualTo(5000);
        assertThat(config.ackMaxRetries()).isEqualTo(3);
        assertThat(config.intervalMs()).isEqualTo(1000);
        assertThat(config.vehicleStartStaggerMs()).isZero();
        assertThat(config.host()).isEqualTo("127.0.0.1");
        assertThat(config.port()).isEqualTo(9000);
        assertThat(config.route().crs()).isEqualTo("WGS84");
        assertThat(config.ackLossDropCount()).isEqualTo(1);
        assertThat(config.commandWindowMs()).isEqualTo(15000);
        assertThat(config.offlineGapMs()).isEqualTo(3000);
        assertThat(config.backfillSeconds()).isEqualTo(120);
        assertThat(config.enabledScenes()).containsExactly(ScenarioType.NORMAL);
    }

    @Test
    void scenarioTogglesSelectOnlyEnabledScenesInCanonicalOrder() {
        ScenarioConfig config = ScenarioConfig.parse(BASE + """
                scene.commands=true
                scene.overspeed=true
                scene.offline=true
                scene.gnss=true
                """);

        assertThat(config.enabledScenes())
                .containsExactly(ScenarioType.NORMAL, ScenarioType.OVERSPEED, ScenarioType.GNSS,
                        ScenarioType.OFFLINE, ScenarioType.COMMANDS);
    }

    @Test
    void rejectsUnknownKey() {
        assertThatThrownBy(() -> ScenarioConfig.parse(BASE + "scenario.normal=true\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知配置项")
                .hasMessageContaining("scenario.normal");
    }

    @Test
    void rejectsMissingRequiredKeys() {
        assertThatThrownBy(() -> ScenarioConfig.parse("scene.normal=true\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seed");
        assertThatThrownBy(() -> ScenarioConfig.parse("seed=1\nscene.normal=true\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vehicles");
        assertThatThrownBy(() -> ScenarioConfig.parse("seed=1\nvehicles=01380022000000000001\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("场景");
    }

    @Test
    void rejectsMalformedVehicleAndPolicyValues() {
        assertThatThrownBy(() -> ScenarioConfig.parse("seed=1\nvehicles=123\nscene.normal=true\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20 位");
        assertThatThrownBy(() -> ScenarioConfig.parse(BASE + "ack-timeout-ms=0\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ack-timeout-ms");
        assertThatThrownBy(() -> ScenarioConfig.parse(BASE + "ack-max-retries=4\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ack-max-retries");
        assertThatThrownBy(() -> ScenarioConfig.parse(BASE + "route.crs=GCJ-02\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WGS84");
    }

    @Test
    void rejectsNegativeVehicleStartStagger() {
        assertThat(ScenarioConfig.parse(BASE + "vehicle-start-stagger-ms=800\n").vehicleStartStaggerMs())
                .isEqualTo(800);
        assertThatThrownBy(() -> ScenarioConfig.parse(BASE + "vehicle-start-stagger-ms=-1\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vehicle-start-stagger-ms")
                .hasMessageContaining("不能为负");
    }

    @Test
    void parsesVehicleListWithoutDuplicates() {
        assertThatThrownBy(() -> ScenarioConfig.parse("""
                seed=1
                vehicles=01380022000000000001,01380022000000000001
                scene.normal=true
                """)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
        assertThat(ScenarioConfig.parse(BASE).vehicles())
                .isEqualTo(List.of("01380022000000000001", "01380022000000000002"));
    }
}
