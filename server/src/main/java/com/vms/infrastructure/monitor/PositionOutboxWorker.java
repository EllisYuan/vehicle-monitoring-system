package com.vms.infrastructure.monitor;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 位置通知与派生缓存分别重试；两者都不进入 TCP ACK 路径。 */
@Component
@EnableScheduling
public class PositionOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(PositionOutboxWorker.class);
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final MonitorWebSocketHandler websocket;
    private final Clock clock;
    public PositionOutboxWorker(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper json,
                                MonitorWebSocketHandler websocket, Clock clock) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.json = json;
        this.websocket = websocket;
        this.clock = clock;
    }

    @Scheduled(fixedRateString = "${vms.monitor.publish-interval-ms:2000}", initialDelay = 2000)
    public void dispatch() {
        try {
            var pending = jdbc.query("""
                    SELECT event_id, event_type, aggregate_id, version, attempts FROM notification_outbox
                    WHERE status IN ('PENDING','RETRY') AND retry_at <= ? ORDER BY retry_at LIMIT 100
                    """, (rs, row) -> new Pending(rs.getString(1), rs.getString(2), rs.getLong(3),
                    rs.getLong(4), rs.getInt(5)), LocalDateTime.now(clock));
            for (Pending event : pending) {
                try {
                    boolean delivered = "ALARM_EVENT".equals(event.eventType())
                            ? websocket.publishAlarm(event.id(), event.aggregateId())
                            : websocket.publish(event.aggregateId(), event.version());
                    if (!delivered) throw new IllegalStateException("WS unavailable");
                    int updated = jdbc.update("UPDATE notification_outbox SET status='DONE', updated_at=? WHERE event_id=? AND version=?",
                            LocalDateTime.now(clock), event.id(), event.version());
                    if (updated == 0) {
                        // 新版本已覆盖当前读到的标记；不要用旧 worker 清掉新版本待发送状态。
                        log.debug("Notification version was superseded: event={}, version={}",
                                event.id(), event.version());
                    }
                } catch (RuntimeException e) {
                    int updated = jdbc.update("""
                            UPDATE notification_outbox SET status = CASE WHEN attempts + 1 >= 10 THEN 'DEAD' ELSE 'RETRY' END,
                                attempts = attempts + 1, retry_at=?, updated_at=?
                            WHERE event_id=? AND version=? AND status IN ('PENDING','RETRY')
                            """, LocalDateTime.now(clock).plusSeconds(2), LocalDateTime.now(clock), event.id(), event.version());
                    if (updated == 0) {
                        log.debug("Notification retry was superseded: event={}, version={}", event.id(), event.version());
                    } else if (event.attempts() + 1 >= 10) {
                        log.error("Notification moved to DEAD: event={}, version={}", event.id(), event.version());
                    } else {
                        log.warn("Notification will retry: event={}, version={}", event.id(), event.version());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Position outbox unavailable; committed positions remain in MySQL");
        }
    }

    /** 单实例目标 100 车；每次 MSET 重建完整派生快照，Redis 丢失 DONE 标记对应缓存也能恢复。 */
    @Scheduled(fixedDelayString = "${vms.monitor.publish-interval-ms:2000}", initialDelay = 2000)
    public void refreshCache() {
        try {
            Map<String, String> values = new HashMap<>();
            for (var row : jdbc.queryForList("SELECT * FROM vehicle_last_position")) {
                values.put("vms:position:" + row.get("vehicle_id"), json.writeValueAsString(row));
            }
            if (!values.isEmpty()) redis.opsForValue().multiSet(values);
        } catch (RuntimeException e) {
            log.warn("Position cache unavailable; next refresh rebuilds it from MySQL");
        }
    }

    /** 位置事件按车合并、报警事件按事件 id 独立投递，路由由 event_type 决定。 */
    private record Pending(String id, String eventType, long aggregateId, long version, int attempts) { }
}
