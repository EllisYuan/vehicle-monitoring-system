package com.vms.simulator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 22：场景编排 CLI。只接受 --config 与 --output-dir，场景组合、seed、时序均由配置文件决定。
 * 退出码：全部终端 OK 为 0；任一终端 FAILED/ABORTED 为 1。失败原因写入 summary 与记录文件。
 */
public final class ScenarioSimulator {

    private ScenarioSimulator() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = null;
        Path outputDir = Path.of("scenario-runs");
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("缺少参数值：" + args[i]);
            }
            switch (args[i]) {
                case "--config" -> configPath = Path.of(args[i + 1]);
                case "--output-dir" -> outputDir = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException("未知参数：" + args[i]);
            }
        }
        if (configPath == null) {
            throw new IllegalArgumentException("必须提供 --config <file>");
        }
        String text = Files.readString(configPath, StandardCharsets.UTF_8);
        ScenarioConfig config = ScenarioConfig.parse(text);
        System.out.println("simulation: scenario runner, simulated WGS84 positions, config=" + configPath);
        RunSummary summary = ScenarioRunner.run(config, outputDir.toAbsolutePath());
        System.out.println("run: id=" + summary.runId() + " success=" + summary.success());
        System.out.println("send-log: " + summary.sendLog());
        System.out.println("ack-log: " + summary.ackLog());
        System.out.println("summary: " + summary.summaryFile());
        for (TerminalResult result : summary.terminals()) {
            System.out.println("terminal: no=" + result.terminalNo() + " status=" + result.status()
                    + " reason=" + result.failureReason() + " sent=" + result.sentCount()
                    + " acks=" + result.ackCount() + " remaining=" + result.remainingEntries());
        }
        if (!summary.success()) {
            System.exit(1);
        }
    }
}
