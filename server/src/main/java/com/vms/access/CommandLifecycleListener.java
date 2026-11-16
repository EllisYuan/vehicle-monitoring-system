package com.vms.access;

import com.vms.protocol.TerminalGeneralResponse;

/** TCP 接入层把终端应答与合法注销交给指令生命周期处理，接入层本身不解释指令状态。 */
public interface CommandLifecycleListener {

    void onTerminalReply(long terminalId, String terminalNo, long epoch, TerminalGeneralResponse response);

    void onLawfulLogout(long terminalId, String terminalNo, long epoch);
}
