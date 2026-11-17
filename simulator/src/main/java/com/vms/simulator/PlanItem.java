package com.vms.simulator;

/** 22：0x0704 批量位置中的单个位置项；gpsOffsetSeconds 相对批量发送时刻。 */
public record PlanItem(long gpsOffsetSeconds, double lng, double lat, int speed, int direction,
                       boolean fixValid) {
}
