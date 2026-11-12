package com.vms.access;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vms.access")
public class TerminalAccessProperties {

    private int tcpPort = 9000;

    public int getTcpPort() {
        return tcpPort;
    }

    public void setTcpPort(int tcpPort) {
        if (tcpPort < 0 || tcpPort > 65535) {
            throw new IllegalArgumentException("TCP 端口超出范围：" + tcpPort);
        }
        this.tcpPort = tcpPort;
    }
}