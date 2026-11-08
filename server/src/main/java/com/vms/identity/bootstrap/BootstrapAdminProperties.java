package com.vms.identity.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vms.bootstrap-admin")
public record BootstrapAdminProperties(String username, String password) {

    public boolean isConfigured() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }
}
