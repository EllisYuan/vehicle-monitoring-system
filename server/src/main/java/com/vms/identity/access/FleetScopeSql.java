package com.vms.identity.access;

import java.util.Collections;
import java.util.List;

/**
 * 数据范围转 SQL 的唯一入口，避免各业务模块自行拼接 IN 子句时遗漏空集合语义。
 */
public final class FleetScopeSql {

    private FleetScopeSql() {
    }

    /**
     * 追加到 WHERE 的片段；参数依序追加到 args。全范围用 1=1，无授权用 1=0。
     */
    public static String where(String column, FleetScope scope, List<Object> args) {
        if (scope.all()) {
            return "1=1";
        }
        if (scope.fleetIds().isEmpty()) {
            return "1=0";
        }
        args.addAll(scope.fleetIds());
        return column + " IN (" + String.join(",", Collections.nCopies(scope.fleetIds().size(), "?")) + ")";
    }
}
