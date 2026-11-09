package com.vms.identity.security;

import java.util.Optional;

/**
 * 固定三角色，由代码定义功能权限，不做可编辑的角色/菜单设计器。
 */
public enum RoleCode {

    ADMIN("管理员"),
    MONITOR("监控员"),
    FLEET_LEADER("车队长");

    private final String label;

    RoleCode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<RoleCode> parse(String code) {
        for (RoleCode role : values()) {
            if (role.name().equals(code)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
