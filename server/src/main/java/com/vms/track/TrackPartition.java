package com.vms.track;

import java.time.LocalDate;

/** information_schema 中的 track_point 日分区；未知手工分区 day 为 null。 */
public record TrackPartition(String name, LocalDate day, LocalDate endExclusive, long estimatedRows) {
}
