package com.vms.identity.access;

import com.vms.identity.account.UserAccount;
import com.vms.identity.security.RoleCode;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据范围统一入口：车队长只能访问授权车队，其他角色为全企业。
 * 客户端传来的 fleet_id 只作为过滤条件，先经本服务判定，不能扩大范围。
 */
@Component
public class FleetScopeResolver {

    private final JdbcTemplate jdbc;

    public FleetScopeResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public FleetScope resolve(UserAccount user) {
        RoleCode role = RoleCode.parse(user.getRoleCode()).orElseThrow(
                () -> new IllegalStateException("未知角色：" + user.getRoleCode()));
        if (role != RoleCode.FLEET_LEADER) {
            return FleetScope.allFleets();
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT fleet_id FROM sys_user_fleet WHERE user_id = ? ORDER BY fleet_id",
                Long.class, user.getId());
        return FleetScope.of(Set.copyOf(ids));
    }
}
