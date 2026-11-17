package com.vms.simulator;

/**
 * 22：综合场景的最小单元。枚举顺序即计划生成顺序，保证相同配置下计划输入序列稳定。
 * 模拟器只编排场景，不实现平台侧的报警、围栏、离线或超速判定。
 */
public enum ScenarioType {
    NORMAL("normal"),
    PARKING("parking"),
    OVERSPEED("overspeed"),
    GNSS("gnss"),
    GEOFENCE("geofence"),
    OFFLINE("offline"),
    BACKFILL("backfill"),
    DUPLICATE("duplicate"),
    ACK_LOSS("ack-loss"),
    COMMANDS("commands");

    private final String key;

    ScenarioType(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static ScenarioType fromKey(String key) {
        for (ScenarioType type : values()) {
            if (type.key.equals(key)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知场景：" + key);
    }
}
