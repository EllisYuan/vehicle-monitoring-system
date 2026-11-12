package com.vms.access;

import java.util.Optional;

/** TCP 接入层对通信状态与会话代次的最小公开边界。 */
public interface TerminalAccessPort {

    int port();

    Optional<SessionView> current(String terminalNo);

    void revoke(long terminalId);

    record SessionView(long epoch, String terminalNo, boolean writable) { }
}