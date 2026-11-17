package com.vms.simulator;

import java.util.List;

/** 22：规范化计划中的一条输入。offsetMs 是相对终端连接开始的时刻，不含墙钟时间。 */
public record PlanEntry(String terminalNo, ScenarioType scene, String kind, long offsetMs, int messageId,
                        double lng, double lat, int speed, int direction, boolean fixValid, int dropAcks,
                        List<PlanItem> items) {

    public static final String CONNECT = "CONNECT";
    public static final String DISCONNECT = "DISCONNECT";
    public static final String POSITION = "POSITION";
    public static final String LOGOUT = "LOGOUT";

    public PlanEntry {
        items = List.copyOf(items);
    }

    public static PlanEntry control(String terminalNo, ScenarioType scene, String kind, long offsetMs,
                                    int messageId) {
        return new PlanEntry(terminalNo, scene, kind, offsetMs, messageId, 0, 0, 0, 0, false, 0, List.of());
    }

    public static PlanEntry position(String terminalNo, ScenarioType scene, long offsetMs, double lng,
                                     double lat, int speed, int direction, boolean fixValid, int dropAcks) {
        return new PlanEntry(terminalNo, scene, POSITION, offsetMs, 0x0200, lng, lat, speed, direction,
                fixValid, dropAcks, List.of());
    }

    public static PlanEntry batch(String terminalNo, ScenarioType scene, long offsetMs, List<PlanItem> items) {
        return new PlanEntry(terminalNo, scene, POSITION, offsetMs, 0x0704, 0, 0, 0, 0, true, 0, items);
    }
}
