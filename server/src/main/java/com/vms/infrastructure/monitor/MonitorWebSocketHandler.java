package com.vms.infrastructure.monitor;

import com.vms.identity.auth.AuthService;
import com.vms.identity.security.Permission;
import com.vms.telemetry.MonitorSnapshotService;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

@Component
public class MonitorWebSocketHandler extends TextWebSocketHandler {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final AuthService auth;
    private final MonitorSnapshotService snapshots;
    private final ObjectMapper json;
    private final Clock clock;
    public MonitorWebSocketHandler(AuthService auth, MonitorSnapshotService snapshots, ObjectMapper json, Clock clock) {
        this.auth = auth;
        this.snapshots = snapshots;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        try {
            auth.requirePermission(auth.requireToken(token(session)), Permission.FLEET_VIEW);
            sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 1000, 64 * 1024));
        } catch (RuntimeException e) {
            session.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    /** 每次输出重新查询当前账号与车辆范围；从不把全量位置交给客户端过滤。 */
    public boolean publish(long vehicleId, long version) {
        boolean delivered = true;
        for (WebSocketSession session : sessions.values()) {
            try {
                var user = auth.requireToken(token(session));
                auth.requirePermission(user, Permission.FLEET_VIEW);
                var vehicle = snapshots.vehicle(user, vehicleId);
                if (vehicle == null || vehicle.position() == null) continue;
                // worker 后续会重新读取更高版本，旧读者只负责自己的版本。
                if (vehicle.position().version() != version) continue;
                var frame = Map.of("type", "vehicle_position", "event_id", "position:" + vehicleId + ":" + version,
                        "aggregate_id", vehicleId, "version", version, "server_time", OffsetDateTime.now(clock),
                        "payload", vehicle);
                session.sendMessage(new TextMessage(json.writeValueAsString(frame)));
            } catch (com.vms.identity.auth.SessionRevokedException e) {
                close(session, CloseStatus.POLICY_VIOLATION);
            } catch (RuntimeException | IOException e) {
                delivered = false;
                close(session, CloseStatus.SERVER_ERROR);
            }
        }
        return delivered;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 订阅范围由身份决定；不接受客户端任意声明 fleet_id 或 vehicle_id。
        session.close(CloseStatus.BAD_DATA);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { sessions.remove(session.getId()); }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) { close(session, CloseStatus.SERVER_ERROR); }

    private String token(WebSocketSession session) { return (String) session.getAttributes().get("sessionToken"); }
    private void close(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        try { session.close(status); } catch (IOException ignored) { }
    }
}
