package com.vms.identity.security;

/**
 * 功能权限；数据范围由 {@link com.vms.identity.access.FleetScopeResolver} 单独判定。
 */
public enum Permission {

    /** 查看车队与受数据范围限定的业务资源。 */
    FLEET_VIEW(RoleCode.ADMIN, RoleCode.MONITOR, RoleCode.FLEET_LEADER),
    /** 维护车队档案；仅管理员。 */
    FLEET_MANAGE(RoleCode.ADMIN),
    /** 账号、角色与车队授权维护；仅管理员。 */
    ACCOUNT_MANAGE(RoleCode.ADMIN);

    private final RoleCode[] roles;

    Permission(RoleCode... roles) {
        this.roles = roles;
    }

    public boolean isGrantedTo(RoleCode role) {
        for (RoleCode granted : roles) {
            if (granted == role) {
                return true;
            }
        }
        return false;
    }
}
