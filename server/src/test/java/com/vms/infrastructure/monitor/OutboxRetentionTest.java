package com.vms.infrastructure.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.vms.support.IntegrationContainers;
import com.vms.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 18 AC5：DONE 通知按实际完成时间保留 7 天；DEAD 与未完成通知永不被清理任务删除。
 * 使用可控业务时钟验证 7 天边界，不依赖真实等待。
 */
@SpringBootTest(properties = {
        "vms.bootstrap-admin.username=retention-admin",
        "vms.bootstrap-admin.password=Retention-Pass-2026",
        "vms.access.tcp-port=0",
        "vms.monitor.publish-interval-ms=3600000",
        "vms.monitor.dispatch-initial-delay-ms=3600000",
        "vms.monitor.cache-initial-delay-ms=3600000",
        "vms.monitor.cleanup-interval-ms=3600000",
        "vms.monitor.cleanup-initial-delay-ms=3600000"
})
@Import({IntegrationContainers.class, OutboxRetentionTest.TimeConfiguration.class})
class OutboxRetentionTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired PositionOutboxWorker worker;
    @Autowired MutableClock clock;

    @BeforeEach
    void clearOutbox() {
        jdbc.update("DELETE FROM notification_outbox");
    }

    @Test
    void doneOlderThanSevenDaysIsDeletedWhileRecentDoneDeadAndPendingAreKept() {
        LocalDateTime now = LocalDateTime.now(clock);
        insert("done-old", "DONE", now.minusDays(8), 1);
        insert("done-recent", "DONE", now.minusDays(6), 0);
        insert("dead-old", "DEAD", now.minusDays(30), 10);
        insert("pending-old", "PENDING", now.minusDays(30), 0);

        worker.cleanupCompleted();

        assertThat(eventIds()).containsExactlyInAnyOrder("done-recent", "dead-old", "pending-old");
    }

    @Test
    void sevenDayBoundaryUsesActualCompletionTime() {
        LocalDateTime now = LocalDateTime.now(clock);
        insert("done-at-boundary", "DONE", now.minusDays(7), 1);
        insert("done-just-under", "DONE", now.minusDays(7).plus(Duration.ofMinutes(1)), 1);

        worker.cleanupCompleted();

        assertThat(eventIds()).containsExactly("done-just-under");
    }

    private List<String> eventIds() {
        return jdbc.queryForList("SELECT event_id FROM notification_outbox ORDER BY event_id", String.class);
    }

    private void insert(String eventId, String status, LocalDateTime updatedAt, int attempts) {
        jdbc.update("""
                INSERT INTO notification_outbox
                    (event_id, event_type, aggregate_id, version, status, retry_at, attempts, updated_at)
                VALUES (?, 'VEHICLE_POSITION', 1, 1, ?, ?, ?, ?)
                """, eventId, status, updatedAt, attempts, updatedAt);
    }

    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock outboxRetentionClock() {
            return new MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneId.of("Asia/Shanghai"));
        }
    }
}
