package com.vms.telemetry;

import com.vms.protocol.PositionReport;
import java.time.LocalDateTime;

/** 实时无效定位的质量观察；与位置事务同提交，异常即回滚位置与报警。 */
public interface PositionQualityParticipant {
    void observe(long vehicleId, long terminalId, long fleetId, PositionReport report, LocalDateTime receivedAt);
}