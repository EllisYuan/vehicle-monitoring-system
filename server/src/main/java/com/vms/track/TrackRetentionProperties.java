package com.vms.track;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 轨迹保留与分区维护的工程默认值。 */
@ConfigurationProperties(prefix = "vms.track")
public record TrackRetentionProperties(
        Integer defaultRetentionDays,
        Integer futureDays,
        Integer minRetentionDays,
        Integer maxRetentionDays,
        String dailyCron) {

    public TrackRetentionProperties {
        defaultRetentionDays = defaultRetentionDays == null ? 30 : defaultRetentionDays;
        futureDays = futureDays == null ? 7 : futureDays;
        minRetentionDays = minRetentionDays == null ? 1 : minRetentionDays;
        maxRetentionDays = maxRetentionDays == null ? 365 : maxRetentionDays;
        dailyCron = dailyCron == null || dailyCron.isBlank() ? "0 5 0 * * *" : dailyCron;
    }
}
