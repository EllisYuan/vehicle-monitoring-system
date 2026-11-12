package com.vms.telemetry;

import com.vms.protocol.PositionReport;
import java.time.LocalDateTime;

/** 报警/围栏可在位置事务内同步参与；异常抛出即回滚位置、状态与 outbox。 */
public interface PositionStateParticipant {
    void apply(long vehicleId, long terminalId, long fleetId, long version,
               PositionReport report, LocalDateTime receivedAt);
}
