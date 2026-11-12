package com.vms.infrastructure.monitor;

import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration
@EnableWebSocket
public class MonitorWebSocketConfig implements WebSocketConfigurer {
    private final MonitorWebSocketHandler handler;
    private final MonitorTicketService tickets;
    public MonitorWebSocketConfig(MonitorWebSocketHandler handler, MonitorTicketService tickets) {
        this.handler = handler;
        this.tickets = tickets;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/monitor").addInterceptors(new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler wsHandler, Map<String, Object> attributes) {
                try {
                    String value = UriComponentsBuilder.fromUri(request.getURI()).build()
                            .getQueryParams().getFirst("ticket");
                    if (value == null || value.length() > 128) {
                        response.setStatusCode(HttpStatus.UNAUTHORIZED);
                        return false;
                    }
                    attributes.put("sessionToken", tickets.consume(value));
                    return true;
                } catch (RuntimeException e) {
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    return false;
                }
            }
            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Exception exception) { }
        });
    }
}
