package com.vms.infrastructure.monitor;

import com.vms.identity.auth.AuthService;
import com.vms.identity.auth.SessionRevokedException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 单实例、30 秒、一次性 ticket；容量有界，长期会话凭证不进入 WS URL。 */
@Service
public class MonitorTicketService {
    private final Map<String, Ticket> tickets = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final AuthService auth;
    private final Clock clock;
    public MonitorTicketService(AuthService auth, Clock clock) {
        this.auth = auth;
        this.clock = clock;
    }

    public synchronized Issued issue(String token) {
        auth.requireToken(token);
        Instant now = clock.instant();
        tickets.values().removeIf(ticket -> !ticket.expiresAt().isAfter(now));
        if (tickets.size() >= 4096) throw new IllegalStateException("WS ticket capacity reached");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = now.plusSeconds(30);
        tickets.put(value, new Ticket(token, expires));
        return new Issued(value, expires.atZone(clock.getZone()).toOffsetDateTime());
    }

    public synchronized String consume(String value) {
        Ticket ticket = tickets.remove(value);
        if (ticket == null || !ticket.expiresAt().isAfter(clock.instant())) {
            throw new SessionRevokedException("INVALID_WS_TICKET");
        }
        auth.requireToken(ticket.token());
        return ticket.token();
    }

    public record Issued(String ticket, OffsetDateTime expiresAt) { }
    private record Ticket(String token, Instant expiresAt) { }
}
