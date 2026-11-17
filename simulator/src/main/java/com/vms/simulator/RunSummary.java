package com.vms.simulator;

import java.nio.file.Path;
import java.util.List;

/** 22：一次场景运行的产物路径与终端结果汇总。 */
public record RunSummary(String runId, Path sendLog, Path ackLog, Path summaryFile, Path manifest,
                         boolean success, List<TerminalResult> terminals) {

    public RunSummary {
        terminals = List.copyOf(terminals);
    }
}
