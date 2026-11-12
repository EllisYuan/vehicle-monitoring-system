package com.vms.access;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class TerminalTcpServer implements TerminalAccessPort, SmartLifecycle {

    private final TerminalAccessProperties properties;
    private final TerminalAccessService access;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Channel serverChannel;
    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile int boundPort;
    private final com.vms.telemetry.PositionIngestService positions;
    private final TerminalSerialDispatcher dispatcher;

    private final java.time.Clock clock;

    public TerminalTcpServer(TerminalAccessProperties properties, TerminalAccessService access,
                             com.vms.telemetry.PositionIngestService positions,
                             TerminalSerialDispatcher dispatcher, java.time.Clock clock) {
        this.properties = properties;
        this.access = access;
        this.positions = positions;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        try {
            serverChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(new TerminalFrameDecoder(clock));
                            channel.pipeline().addLast("terminalDispatch", new TerminalDispatchHandler(dispatcher));
                            channel.pipeline().addLast("terminalAccess", new TerminalFrameHandler(access, positions));
                        }
                    })
                    .bind(new InetSocketAddress("127.0.0.1", properties.getTcpPort()))
                    .syncUninterruptibly()
                    .channel();
            boundPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
            running.set(true);
        } catch (RuntimeException e) {
            stopGroups();
            throw e;
        }
    }

    @Override
    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            stopGroups();
            return;
        }
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        stopGroups();
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    private void stopGroups() {
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
    }

    @Override
    public int port() {
        if (!running.get()) {
            throw new IllegalStateException("TCP listener 尚未启动");
        }
        return boundPort;
    }

    @Override
    public java.util.Optional<SessionView> current(String terminalNo) {
        return access.current(terminalNo);
    }

    @Override
    public void revoke(long terminalId) {
        access.revoke(terminalId);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}