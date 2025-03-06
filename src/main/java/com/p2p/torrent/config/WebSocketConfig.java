package com.p2p.torrent.config;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.server.HandshakeInterceptor;

import lombok.extern.slf4j.Slf4j;

@Configuration
@EnableWebSocketMessageBroker
@Slf4j
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Bean
    public ThreadPoolTaskScheduler customMessageBrokerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(8);
        scheduler.setThreadNamePrefix("websocket-heartbeat-thread-");
        return scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new LoggingHandshakeInterceptor())
                .withSockJS();
                
        // Add endpoint without SockJS for direct WebSocket connections
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new LoggingHandshakeInterceptor());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue", "/user")
                .setTaskScheduler(customMessageBrokerTaskScheduler())
                .setHeartbeatValue(new long[] {5000, 5000});
        
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        
        log.info("WEBSOCKET CONFIG: Enabled broker with heartbeat of 5 seconds");
    }
    
    /**
     * Set buffer size for WebSocket messages
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        // Increase message size limits (default is 64KB)
        registry.setMessageSizeLimit(2 * 1024 * 1024); // 2MB
        registry.setSendBufferSizeLimit(4 * 1024 * 1024); // 4MB
        registry.setSendTimeLimit(20 * 1000); // 20 seconds
        
        log.info("WEBSOCKET CONFIG: Increased message buffer size to 2MB/4MB");
    }
    
    /**
     * Monitor WebSocket connection events
     */
    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        log.info("WEBSOCKET CONNECTED: New connection established, session id: {}", 
                headerAccessor.getSessionId());
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        log.info("WEBSOCKET DISCONNECTED: Connection closed, session id: {}", 
                headerAccessor.getSessionId());
    }
    
    /**
     * Custom interceptor to log WebSocket connection details
     */
    private static class LoggingHandshakeInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                      WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
            log.info("WEBSOCKET CONNECT: New connection from {}", request.getRemoteAddress());
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                  WebSocketHandler wsHandler, Exception exception) {
            if (exception != null) {
                log.error("WEBSOCKET ERROR: {}", exception.getMessage());
            } else {
                log.info("WEBSOCKET READY: Connected client {}", request.getRemoteAddress());
            }
        }
    }
}