package com.vms.access;

import com.vms.protocol.BodyProperties;
import com.vms.protocol.Frame;
import com.vms.protocol.FrameCodec;
import com.vms.protocol.PlatformGeneralResponse;
import com.vms.protocol.TerminalAuthentication;
import com.vms.protocol.TerminalGeneralResponse;
import com.vms.protocol.TerminalRegisterResponse;
import com.vms.protocol.TerminalRegister;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.buffer.Unpooled;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TerminalFrameHandler extends SimpleChannelInboundHandler<TerminalFrameDecoder.ReceivedFrame> {

    private static final Logger log = LoggerFactory.getLogger(TerminalFrameHandler.class);
    private static final AttributeKey<TerminalAccessService.Registration> REGISTRATION =
            AttributeKey.valueOf("vms.terminal.registration");
    private static final AttributeKey<TerminalConnectionRegistry.Session> SESSION =
            AttributeKey.valueOf("vms.terminal.session");

    private final TerminalAccessService access;
    private final com.vms.telemetry.PositionIngestService positions;

    TerminalFrameHandler(TerminalAccessService access, com.vms.telemetry.PositionIngestService positions) {
        this.access = access;
        this.positions = positions;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, TerminalFrameDecoder.ReceivedFrame received) {
        Frame frame = received.frame();
        int messageId = frame.header().messageId();
        switch (messageId) {
            case 0x0100 -> register(context, frame);
            case 0x0102 -> authenticate(context, frame);
            case 0x0001 -> terminalResponse(context, frame);
            case 0x0002 -> heartbeat(context, frame);
            case 0x0003 -> logout(context, frame);
            case 0x0200 -> position(context, frame, received.receivedAt());
            default -> context.close();
        }
    }

    private void register(ChannelHandlerContext context, Frame frame) {
        TerminalRegister registration;
        try {
            registration = TerminalRegister.parse(frame.body());
        } catch (RuntimeException e) {
            context.close();
            return;
        }
        if (!frame.header().terminalPhone().equals(registration.terminalId())) {
            send(context, frame, 0x8100,
                    new TerminalRegisterResponse(frame.header().serialNumber(),
                            TerminalAccessService.REGISTER_REJECTED, null).encode());
            context.close();
            return;
        }
        TerminalAccessService.RegistrationResult result = access.register(frame.header().terminalPhone());
        String authCode = result.registration() == null ? null : result.registration().authCode();
        if (result.registration() != null) {
            context.channel().attr(REGISTRATION).set(result.registration());
        }
        send(context, frame, 0x8100,
                new TerminalRegisterResponse(frame.header().serialNumber(), result.result(), authCode).encode());
        if (result.registration() == null) {
            context.close();
        }
    }

    private void authenticate(ChannelHandlerContext context, Frame frame) {
        TerminalAuthentication authentication = TerminalAuthentication.parse(frame.body());
        TerminalAccessService.Registration registration = context.channel().attr(REGISTRATION).get();
        if (registration == null || !registration.terminalNo().equals(frame.header().terminalPhone())) {
            context.close();
            return;
        }
        TerminalConnectionRegistry.Session session = access.authenticate(registration,
                authentication.authCode(), context.channel());
        int result = session == null ? TerminalAccessService.AUTHENTICATION_FAILED
                : TerminalAccessService.AUTHENTICATION_SUCCESS;
        if (session != null) {
            context.channel().attr(SESSION).set(session);
            context.channel().attr(REGISTRATION).set(null);
        }
        send(context, frame, 0x8001,
                new PlatformGeneralResponse(frame.header().serialNumber(), 0x0102, result).encode());
        if (session == null) {
            context.close();
        }
    }

    private void terminalResponse(ChannelHandlerContext context, Frame frame) {
        if (!current(context, frame.header().terminalPhone())) {
            context.close();
            return;
        }
        TerminalGeneralResponse.parse(frame.body());
        if (!access.touch(currentSession(context, frame.header().terminalPhone()))) {
            context.close();
        }
    }

    private void heartbeat(ChannelHandlerContext context, Frame frame) {
        TerminalConnectionRegistry.Session session = currentSession(context, frame.header().terminalPhone());
        if (session == null) {
            context.close();
            return;
        }
        if (!access.touch(session)) {
            access.logout(session);
            context.close();
            return;
        }
        send(context, frame, 0x8001,
                new PlatformGeneralResponse(frame.header().serialNumber(), 0x0002, 0).encode());
    }

    private void position(ChannelHandlerContext context, Frame frame, java.time.LocalDateTime receivedAt) {
        TerminalConnectionRegistry.Session session = currentSession(context, frame.header().terminalPhone());
        if (session == null) {
            context.close();
            return;
        }
        positions.accept(session, com.vms.protocol.PositionReport.parse(frame.body()), receivedAt);
        // accept 经 Spring transaction proxy 返回时已提交；Redis/WS 仅由 outbox worker 执行。
        send(context, frame, 0x8001,
                new PlatformGeneralResponse(frame.header().serialNumber(), 0x0200, 0).encode());
    }

    private void logout(ChannelHandlerContext context, Frame frame) {
        TerminalConnectionRegistry.Session session = currentSession(context, frame.header().terminalPhone());
        if (session == null) {
            context.close();
            return;
        }
        Frame response = response(frame, 0x8001,
                new PlatformGeneralResponse(frame.header().serialNumber(), 0x0003, 0).encode());
        access.logout(session);
        context.writeAndFlush(Unpooled.wrappedBuffer(FrameCodec.encode(response)))
                .addListener(future -> context.close());
    }

    private boolean current(ChannelHandlerContext context, String terminalNo) {
        return currentSession(context, terminalNo) != null;
    }

    private TerminalConnectionRegistry.Session currentSession(ChannelHandlerContext context, String terminalNo) {
        TerminalConnectionRegistry.Session session = context.channel().attr(SESSION).get();
        return session != null && session.terminalNo().equals(terminalNo) && access.isCurrent(session)
                ? session : null;
    }

    private void send(ChannelHandlerContext context, Frame request, int messageId, byte[] body) {
        byte[] encoded = FrameCodec.encode(response(request, messageId, body));
        context.writeAndFlush(Unpooled.wrappedBuffer(encoded)).addListener(future -> {
            if (!future.isSuccess()) {
                log.warn("TCP response write failed", future.cause());
            }
        });
    }

    private Frame response(Frame request, int messageId, byte[] body) {
        return new Frame(new com.vms.protocol.MessageHeader(messageId,
                BodyProperties.plain(body.length), 1, request.header().terminalPhone(),
                request.header().serialNumber()), body);
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
        context.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        access.logout(context.channel().attr(SESSION).get());
        context.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        if (cause instanceof com.vms.protocol.MalformedFrameException
                || cause instanceof com.vms.protocol.UnsupportedMessageException) {
            log.debug("关闭无效 JT/T 808 frame 的 TCP 连接：{}", cause.getMessage());
        } else {
            log.warn("终端 TCP 连接处理失败", cause);
        }
        context.close();
    }
}
