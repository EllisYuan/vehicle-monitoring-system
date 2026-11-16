package com.vms.access;

import com.vms.protocol.Frame;
import java.util.Optional;

/** TCP 接入层对通信状态、会话代次与下行发送的最小公开边界。 */
public interface TerminalAccessPort {

    int port();

    Optional<SessionView> current(String terminalNo);

    void revoke(long terminalId);

    /** 只在当前已鉴权且代次匹配的 Channel 上发送；离线不排队。 */
    SendResult send(String terminalNo, long expectedEpoch, Frame frame);

    record SessionView(long epoch, String terminalNo, boolean writable) { }

    enum SendResult { SENT, NOT_CONNECTED, STALE_EPOCH, WRITE_FAILED }
}
