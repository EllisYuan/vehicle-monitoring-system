package com.vms.identity.account;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * sys_user_fleet 为纯授权关系表，使用显式 SQL 完成替换与范围查询。
 */
@Repository
public class UserFleetRepository {

    private final JdbcTemplate jdbc;

    public UserFleetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> findFleetIds(long userId) {
        return jdbc.queryForList(
                "SELECT fleet_id FROM sys_user_fleet WHERE user_id = ? ORDER BY fleet_id",
                Long.class, userId);
    }

    public Map<Long, List<Long>> findFleetIdsByUser() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id, fleet_id FROM sys_user_fleet ORDER BY user_id, fleet_id");
        Map<Long, List<Long>> byUser = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            long userId = ((Number) row.get("user_id")).longValue();
            long fleetId = ((Number) row.get("fleet_id")).longValue();
            byUser.computeIfAbsent(userId, key -> new ArrayList<>()).add(fleetId);
        }
        return byUser;
    }

    public void replace(long userId, List<Long> fleetIds, LocalDateTime now) {
        jdbc.update("DELETE FROM sys_user_fleet WHERE user_id = ?", userId);
        for (Long fleetId : fleetIds) {
            jdbc.update(
                    "INSERT INTO sys_user_fleet (user_id, fleet_id, created_at) VALUES (?, ?, ?)",
                    userId, fleetId, now);
        }
    }

    public long countExisting(List<Long> fleetIds) {
        if (fleetIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(fleetIds.size(), "?"));
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM fleet WHERE id IN (" + placeholders + ")",
                Long.class, fleetIds.toArray());
        return count == null ? 0 : count;
    }
}
