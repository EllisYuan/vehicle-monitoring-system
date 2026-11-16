package com.vms.track;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * track_point 日分区的元数据读取与 DDL。分区名固定为 pYYYYMMDD，
 * 上界为次日 00:00；未知手工分区不参与清理，避免误删。
 */
@Component
public class TrackPartitionCatalog {

    public static final String TABLE = "track_point";
    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbc;

    public TrackPartitionCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TrackPartition> list() {
        Integer exists = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                """, Integer.class, TABLE);
        if (exists == null || exists == 0) {
            throw new IllegalStateException("track_point 表不存在，无法维护分区");
        }
        return jdbc.query("""
                SELECT PARTITION_NAME, PARTITION_DESCRIPTION, TABLE_ROWS
                FROM information_schema.PARTITIONS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND PARTITION_NAME IS NOT NULL
                ORDER BY PARTITION_ORDINAL_POSITION
                """, (rs, row) -> {
            String name = rs.getString(1);
            String description = rs.getString(2);
            long rows = rs.getLong(3);
            return new TrackPartition(name, parseDay(name), parseEnd(description), rows);
        }, TABLE);
    }

    /** 补齐 [start, end] 内缺失的日分区；返回本次新建的分区名。 */
    public List<String> ensureWindow(LocalDate start, LocalDate end) {
        if (start == null || end == null || start.isAfter(end)) {
            throw new IllegalArgumentException("分区窗口不合法");
        }
        Map<LocalDate, TrackPartition> byDay = new HashMap<>();
        for (TrackPartition partition : list()) {
            if (partition.day() != null && partition.endExclusive() != null) {
                byDay.put(partition.day(), partition);
            }
        }
        List<String> created = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            if (byDay.containsKey(day)) {
                continue;
            }
            LocalDate missingDay = day;
            TrackPartition next = byDay.values().stream()
                    .filter(partition -> partition.day().isAfter(missingDay))
                    .min(Comparator.comparing(TrackPartition::day))
                    .orElse(null);
            String name = partitionName(day);
            if (next == null) {
                jdbc.execute("ALTER TABLE " + TABLE + " ADD PARTITION (PARTITION " + name
                        + " VALUES LESS THAN ('" + day.plusDays(1) + "'))");
                byDay.put(day, new TrackPartition(name, day, day.plusDays(1), 0));
            } else {
                // 在已有分区前插入缺失日；REORGANIZE 保留 next 分区的数据。
                jdbc.execute("ALTER TABLE " + TABLE + " REORGANIZE PARTITION " + next.name()
                        + " INTO (PARTITION " + name + " VALUES LESS THAN ('" + day.plusDays(1) + "'),"
                        + " PARTITION " + next.name() + " VALUES LESS THAN ('" + next.endExclusive() + "'))");
                byDay.put(day, new TrackPartition(name, day, day.plusDays(1), 0));
            }
            created.add(name);
        }
        return created;
    }

    /** 删除早于 cutoff 的日分区（PMAX 与未知分区不动），返回删除范围与行数估算。 */
    public DropResult dropBefore(LocalDate cutoff) {
        List<String> dropped = new ArrayList<>();
        long estimatedRows = 0;
        for (TrackPartition partition : list()) {
            if (partition.day() != null && partition.day().isBefore(cutoff)) {
                estimatedRows += partition.estimatedRows();
                jdbc.execute("ALTER TABLE " + TABLE + " DROP PARTITION " + partition.name());
                dropped.add(partition.name());
            }
        }
        return new DropResult(dropped, estimatedRows);
    }

    public record DropResult(List<String> partitions, long estimatedRows) {
    }

    public static String partitionName(LocalDate day) {
        return "p" + day.format(NAME_FORMAT);
    }

    private static LocalDate parseDay(String name) {
        if (name == null || name.length() != 9 || name.charAt(0) != 'p') {
            return null;
        }
        try {
            return LocalDate.parse(name.substring(1), NAME_FORMAT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalDate parseEnd(String description) {
        if (description == null) {
            return null;
        }
        String value = description.replace("'", "").trim();
        if (value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
