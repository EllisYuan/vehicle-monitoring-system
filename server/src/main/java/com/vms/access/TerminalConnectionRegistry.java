package com.vms.access;

import io.netty.channel.Channel;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** 当前连接唯一权威，Redis 或数据库残留状态不能替代这里的 Channel 与 epoch。 */
@Component
public class TerminalConnectionRegistry {

    private final AtomicLong epochs = new AtomicLong();
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();

    public Session replace(String terminalNo, long terminalId, Channel channel) {
        Session next = new Session(terminalNo, terminalId, epochs.incrementAndGet(), channel);
        Session previous = sessions.put(terminalNo, next);
        if (previous != null && previous.channel() != channel) {
            previous.channel().close();
        }
        return next;
    }

    public boolean isCurrent(Session session) {
        return session != null && sessions.get(session.terminalNo()) == session
                && session.channel().isActive();
    }

    public boolean removeIfCurrent(Session session) {
        return session != null && sessions.remove(session.terminalNo(), session);
    }

    public void revoke(long terminalId) {
        sessions.values().stream()
                .filter(session -> session.terminalId() == terminalId)
                .forEach(session -> {
                    if (sessions.remove(session.terminalNo(), session)) {
                        session.channel().close();
                    }
                });
    }

    public Optional<TerminalAccessPort.SessionView> current(String terminalNo) {
        Session session = sessions.get(terminalNo);
        if (session == null) {
            return Optional.empty();
        }
        return Optional.of(new TerminalAccessPort.SessionView(session.epoch(), terminalNo,
                session.channel().isActive() && isCurrent(session)));
    }

    public record Session(String terminalNo, long terminalId, long epoch, Channel channel) { }
}