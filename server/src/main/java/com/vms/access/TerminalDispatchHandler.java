package com.vms.access;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.concurrent.RejectedExecutionException;

/** 将完整 frame 按 header terminal_no 交给终端级串行队列。 */
final class TerminalDispatchHandler extends ChannelInboundHandlerAdapter {
    private final TerminalSerialDispatcher dispatcher;

    TerminalDispatchHandler(TerminalSerialDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!(message instanceof TerminalFrameDecoder.ReceivedFrame received)) {
            context.fireChannelRead(message);
            return;
        }
        String terminalNo = received.frame().header().terminalPhone();
        try {
            dispatcher.execute(terminalNo,
                    () -> context.fireChannelRead(received),
                    context::close);
        } catch (RejectedExecutionException exception) {
            context.close();
        }
    }
}
