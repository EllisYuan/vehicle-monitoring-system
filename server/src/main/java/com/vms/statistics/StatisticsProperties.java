package com.vms.statistics;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 统计页的工程默认值；“启动待确认”宽限与告警离线判定保持同一生产口径。 */
@ConfigurationProperties(prefix = "vms.statistics")
public record StatisticsProperties(Integer startupGraceSeconds) {

    public StatisticsProperties {
        startupGraceSeconds = startupGraceSeconds == null ? 180 : Math.max(0, startupGraceSeconds);
    }
}
