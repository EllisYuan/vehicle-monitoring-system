package com.vms.identity.access;

import java.util.Set;

/**
 * 解析结果：全部车队（管理员、监控员）或车队长被明确授权的车队集合。
 */
public record FleetScope(boolean all, Set<Long> fleetIds) {

    public static FleetScope allFleets() {
        return new FleetScope(true, Set.of());
    }

    public static FleetScope of(Set<Long> fleetIds) {
        return new FleetScope(false, Set.copyOf(fleetIds));
    }

    public boolean contains(Long fleetId) {
        return all || (fleetId != null && fleetIds.contains(fleetId));
    }
}
