package com.vms.track;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 读取并缓存 system_config 中的 track.retention.days。 */
@Service
public class TrackRetentionPolicy {

    public static final String CONFIG_KEY = "track.retention.days";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TrackRetentionProperties properties;
    private volatile Integer retentionDays;

    public TrackRetentionPolicy(JdbcTemplate jdbc, Clock clock, TrackRetentionProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
    }

    public synchronized void refresh() {
        List<String> values = jdbc.queryForList(
                "SELECT config_value FROM system_config WHERE config_key = ?", String.class, CONFIG_KEY);
        retentionDays = values.isEmpty() ? properties.defaultRetentionDays() : parse(values.getFirst());
    }

    public int retentionDays() {
        Integer current = retentionDays;
        if (current == null) {
            synchronized (this) {
                if (retentionDays == null) {
                    refresh();
                }
                current = retentionDays;
            }
        }
        return current;
    }

    public long configVersion() {
        List<Long> versions = jdbc.queryForList(
                "SELECT version FROM system_config WHERE config_key = ?", Long.class, CONFIG_KEY);
        return versions.isEmpty() ? 0L : versions.getFirst();
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public LocalDate earliestRetainedDate() {
        return today().minusDays(retentionDays() - 1L);
    }

    public LocalDate prebuildThrough() {
        return today().plusDays(properties.futureDays());
    }

    /** 落在已维护分区窗口内的定位才允许写 track_point。 */
    public boolean isWithinTrackWindow(LocalDateTime gpsTime) {
        if (gpsTime == null) {
            return false;
        }
        LocalDate day = gpsTime.toLocalDate();
        return !day.isBefore(earliestRetainedDate()) && !day.isAfter(prebuildThrough());
    }

    public TrackRetentionProperties properties() {
        return properties;
    }

    private int parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException e) {
            return properties.defaultRetentionDays();
        }
    }
}
