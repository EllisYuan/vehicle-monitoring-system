package com.vms.infrastructure.monitor;

import com.vms.audit.AuditService;
import com.vms.identity.account.UserAccount;
import com.vms.identity.auth.AuthService;
import com.vms.identity.auth.SessionRevokedException;
import com.vms.identity.security.ForbiddenException;
import com.vms.identity.security.Permission;
import com.vms.telemetry.MonitorSnapshotService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * 17：WS 订阅在握手鉴权完成后才进入订阅列表；输出前按最新账号与车队权限过滤。
 * 定期复权覆盖无位置变化时的停用、改密、新登录替换、撤权与到期；慢客户端使用有界待发送缓冲，
 * 超限断开而不是无限排队。撤权与重连路径写必要审计，detail 不含 ticket/token 原文。
 */
@Component
public class MonitorWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(MonitorWebSocketHandler.class);
    /** 授权失效时使用的策略性关闭；客户端据此停止旧订阅并重建。 */
    static final CloseStatus SUBSCRIPTION_REVOKED = new CloseStatus(1008, "SUBSCRIPTION_REVOKED");
    /** 慢客户端缓冲超限：会话不可靠，必须断开后由客户端重建。 */
    static final CloseStatus SLOW_CLIENT = CloseStatus.SESSION_NOT_RELIABLE;

    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AuthService auth;
    private final MonitorSnapshotService snapshots;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final int outboundCapacity;
    private final long revalidateIntervalMillis;
    private final ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "monitor-ws-revalidate");
        thread.setDaemon(true);
        return thread;
    });

    public MonitorWebSocketHandler(AuthService auth, MonitorSnapshotService snapshots, AuditService audit,
                                   ObjectMapper json, Clock clock,
                                   @Value("${vms.monitor.ws-outbound-capacity:512}") int outboundCapacity,
                                   @Value("${vms.monitor.ws-revalidate-interval-ms:1000}") long revalidateIntervalMillis) {
        this.auth = auth;
        this.snapshots = snapshots;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.outboundCapacity = outboundCapacity;
        this.revalidateIntervalMillis = revalidateIntervalMillis;
    }

    @PostConstruct
    void startRevalidation() {
        if (revalidateIntervalMillis > 0) {
            sweeper.scheduleWithFixedDelay(this::revalidateSubscriptions,
                    revalidateIntervalMillis, revalidateIntervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    void shutdown() {
        sweeper.shutdownNow();
        sender.shutdownNow();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UserAccount user;
        try {
            user = auth.requireToken(token(session));
            auth.requirePermission(user, Permission.FLEET_VIEW);
        } catch (RuntimeException e) {
            // 握手鉴权未通过：不进入订阅列表
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(session, 1000, 256 * 1024);
        Subscription subscription = new Subscription(decorated, token(session), user.getId(), user.getUsername(),
                sourceIp(session), outboundCapacity);
        subscriptions.put(decorated.getId(), subscription);
        auditQuietly(AuditService.CATEGORY_LOGIN, "WS_SUBSCRIBE", user, sourceIp(session),
                "sessionId=" + decorated.getId());
        log.info("WS 订阅建立：session={}, user={}", decorated.getId(), user.getUsername());
    }

    /** 每次输出重新查询当前账号与车辆范围；从不把全量位置交给客户端过滤。 */
    public boolean publish(long vehicleId, long version) {
        boolean complete = true;
        String frame = null;
        for (Subscription subscription : subscriptions.values()) {
            if (subscription.closed.get()) continue;
            try {
                UserAccount user = auth.requireToken(subscription.token);
                auth.requirePermission(user, Permission.FLEET_VIEW);
                var vehicle = snapshots.vehicle(user, vehicleId);
                if (vehicle == null || vehicle.position() == null) continue;
                // worker 后续会重新读取更高版本，旧读者只负责自己的版本。
                if (vehicle.position().version() != version) continue;
                if (frame == null) frame = frame(vehicleId, version, vehicle);
                subscription.offer(frame);
            } catch (SessionRevokedException e) {
                revoke(subscription, e.code());
            } catch (ForbiddenException e) {
                revoke(subscription, "PERMISSION_REVOKED");
            } catch (RuntimeException e) {
                // 瞬时基础设施故障：本 tick 视为未完成，由 outbox 重试；不误关仍有效的订阅。
                complete = false;
            }
        }
        return complete;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 订阅范围由身份决定；不接受客户端任意声明 fleet_id 或 vehicle_id。
        removeSubscription(session.getId());
        closeQuietly(session, CloseStatus.BAD_DATA);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { removeSubscription(session.getId()); }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        removeSubscription(session.getId());
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    private String frame(long vehicleId, long version, Object vehicle) {
        return json.writeValueAsString(Map.of("type", "vehicle_position",
                "event_id", "position:" + vehicleId + ":" + version,
                "aggregate_id", vehicleId, "version", version,
                "server_time", OffsetDateTime.now(clock), "payload", vehicle));
    }

    /** 无位置变化时也要在 5 秒内停止旧授权订阅；这是 17 相对 07 的增量。 */
    private void revalidateSubscriptions() {
        for (Subscription subscription : subscriptions.values()) {
            if (subscription.closed.get()) continue;
            try {
                UserAccount user = auth.requireToken(subscription.token);
                auth.requirePermission(user, Permission.FLEET_VIEW);
            } catch (SessionRevokedException e) {
                revoke(subscription, e.code());
            } catch (ForbiddenException e) {
                revoke(subscription, "PERMISSION_REVOKED");
            } catch (RuntimeException e) {
                log.debug("WS 订阅复权暂时不可用：session={}", subscription.id);
            }
        }
    }

    private void revoke(Subscription subscription, String reason) {
        if (!subscription.markClosed()) return;
        subscriptions.remove(subscription.id);
        subscription.outbound.clear();
        auditQuietly(AuditService.CATEGORY_AUTHORIZATION, "WS_SUBSCRIPTION_REVOKED",
                subscription.userId, subscription.username, subscription.sourceIp,
                "sessionId=" + subscription.id + ";reason=" + reason);
        log.info("WS 订阅撤销：session={}, user={}, reason={}", subscription.id, subscription.username, reason);
        closeLater(subscription.session, SUBSCRIPTION_REVOKED);
    }

    private void slowClient(Subscription subscription) {
        if (!subscription.markClosed()) return;
        subscriptions.remove(subscription.id);
        subscription.outbound.clear();
        log.warn("WS 慢客户端缓冲超限，断开订阅：session={}, user={}, capacity={}",
                subscription.id, subscription.username, outboundCapacity);
        closeLater(subscription.session, SLOW_CLIENT);
    }

    private void sendFailed(Subscription subscription, Throwable error) {
        if (!subscription.markClosed()) return;
        subscriptions.remove(subscription.id);
        subscription.outbound.clear();
        log.info("WS 发送失败，移除订阅：session={}, user={}, reason={}",
                subscription.id, subscription.username, error.getClass().getSimpleName());
        closeLater(subscription.session, CloseStatus.SERVER_ERROR);
    }

    private void removeSubscription(String sessionId) {
        Subscription subscription = subscriptions.remove(sessionId);
        if (subscription != null) subscription.markClosed();
    }

    private void closeLater(WebSocketSession session, CloseStatus status) {
        try {
            sender.execute(() -> closeQuietly(session, status));
        } catch (RuntimeException e) {
            closeQuietly(session, status);
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException | RuntimeException e) {
            log.debug("WS 会话关闭失败：session={}", session.getId());
        }
    }

    private void auditQuietly(String category, String action, UserAccount user, String sourceIp, String detail) {
        auditQuietly(category, action, user.getId(), user.getUsername(), sourceIp, detail);
    }

    private void auditQuietly(String category, String action, long userId, String username,
                              String sourceIp, String detail) {
        try {
            audit.record(category, action, "ws/monitor", AuditService.RESULT_SUCCESS,
                    userId, username, sourceIp, detail);
        } catch (RuntimeException e) {
            log.warn("WS 订阅审计写入失败：action={}", action);
        }
    }

    private String token(WebSocketSession session) { return (String) session.getAttributes().get("sessionToken"); }

    private String sourceIp(WebSocketSession session) {
        if (!(session.getRemoteAddress() instanceof InetSocketAddress address)) return null;
        return address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress();
    }

    /** 有界待发送队列 + 单会话串行发送；发送线程不占用 outbox 调度线程。 */
    private final class Subscription {
        private final WebSocketSession session;
        private final String id;
        private final String token;
        private final long userId;
        private final String username;
        private final String sourceIp;
        private final BlockingQueue<String> outbound;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean draining = new AtomicBoolean();

        private Subscription(WebSocketSession session, String token, long userId, String username,
                             String sourceIp, int capacity) {
            this.session = session;
            this.id = session.getId();
            this.token = token;
            this.userId = userId;
            this.username = username;
            this.sourceIp = sourceIp;
            this.outbound = new ArrayBlockingQueue<>(capacity);
        }
        private void offer(String frame) {
            if (closed.get()) return;
            if (!outbound.offer(frame)) {
                slowClient(this);
                return;
            }
            drainLater();
        }

        private void drainLater() {
            if (draining.compareAndSet(false, true)) sender.execute(this::drain);
        }

        private void drain() {
            try {
                String next;
                while (!closed.get() && (next = outbound.poll()) != null) {
                    session.sendMessage(new TextMessage(next));
                }
            } catch (IOException | RuntimeException e) {
                sendFailed(this, e);
            } finally {
                draining.set(false);
                if (!closed.get() && !outbound.isEmpty()) drainLater();
            }
        }

        private boolean markClosed() { return closed.compareAndSet(false, true); }
    }
}