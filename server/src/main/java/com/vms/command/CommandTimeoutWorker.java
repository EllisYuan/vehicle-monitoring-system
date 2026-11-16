package com.vms.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性把超过 30 秒未应答的指令记为 TIMEOUT；只落状态，不自动重发。
 * 读取接口也会按业务时钟补充推进，保证界面不依赖扫描节奏。
 */
@Component
public class CommandTimeoutWorker {

    private static final Logger log = LoggerFactory.getLogger(CommandTimeoutWorker.class);

    private final CommandService commands;

    public CommandTimeoutWorker(CommandService commands) {
        this.commands = commands;
    }

    @Scheduled(fixedDelayString = "${vms.command.timeout-scan-interval-ms:1000}", initialDelay = 1000)
    public void scan() {
        try {
            commands.expireOverdue();
        } catch (RuntimeException e) {
            log.warn("指令超时扫描失败", e);
        }
    }
}
