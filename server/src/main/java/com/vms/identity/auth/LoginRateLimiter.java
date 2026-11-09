package com.vms.identity.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {

    private static final int MAX_SOURCES = 10_000;
    private final Clock clock;
    private final LoginPolicyProperties properties;
    private final Map<String, ArrayDeque<Attempt>> attempts = new HashMap<>();

    public LoginRateLimiter(Clock clock, LoginPolicyProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    public synchronized Permit acquire(String sourceIp) {
        String key = sourceIp == null ? "unknown" : sourceIp;
        long now = clock.millis();
        long window = Duration.ofSeconds(properties.rateLimitWindowSeconds()).toMillis();
        if (attempts.size() >= MAX_SOURCES) {
            attempts.entrySet().removeIf(entry -> {
                prune(entry.getValue(), now - window);
                return entry.getValue().isEmpty();
            });
            if (!attempts.containsKey(key) && attempts.size() >= MAX_SOURCES) {
                throw new LoginRateLimitedException(Duration.ofMillis(window));
            }
        }
        ArrayDeque<Attempt> queue = attempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        prune(queue, now - window);
        if (queue.size() >= properties.rateLimitMaxAttempts()) {
            throw new LoginRateLimitedException(Duration.ofMillis(
                    Math.max(1000, queue.getFirst().startedAt + window - now)));
        }
        Attempt attempt = new Attempt(now);
        queue.addLast(attempt);
        return new Permit(key, attempt);
    }

    public synchronized void reset() {
        attempts.clear();
    }

    private void prune(ArrayDeque<Attempt> queue, long cutoff) {
        queue.removeIf(attempt -> attempt.startedAt <= cutoff && !attempt.inFlight);
    }

    private static final class Attempt {
        private final long startedAt;
        private boolean inFlight = true;

        private Attempt(long startedAt) {
            this.startedAt = startedAt;
        }
    }

    public final class Permit implements AutoCloseable {
        private final String key;
        private final Attempt attempt;
        private boolean failed;

        private Permit(String key, Attempt attempt) {
            this.key = key;
            this.attempt = attempt;
        }

        public void failed() {
            failed = true;
        }

        @Override
        public void close() {
            synchronized (LoginRateLimiter.this) {
                attempt.inFlight = false;
                if (!failed) {
                    ArrayDeque<Attempt> queue = attempts.get(key);
                    if (queue != null) {
                        queue.remove(attempt);
                        if (queue.isEmpty()) {
                            attempts.remove(key);
                        }
                    }
                }
            }
        }
    }
}
