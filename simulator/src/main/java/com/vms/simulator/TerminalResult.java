package com.vms.simulator;

/** 22：单终端执行结果；status 为 OK / FAILED / ABORTED，失败原因必须非空。 */
public record TerminalResult(String terminalNo, String status, String failureReason, long sentCount,
                             long ackCount, int remainingEntries) {
}
