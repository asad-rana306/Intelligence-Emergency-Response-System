package com.iers.realtime.config;

import com.iers.realtime.websocket.StompGuardInterceptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * The live channel.
 *
 *   Connect ....... /ws/live (SockJS), or plain WebSocket at /ws/live/websocket (use this one in apps)
 *   Listen ........ SUBSCRIBE /user/queue/events   and   /user/queue/call
 *   Send .......... SEND /app/call/{incidentId}   and   /app/snapshot
 *
 * "/user/queue/..." is private: the broker maps it to the queue of the connected user.
 * Heartbeats every 10 s keep the connection alive through the gateway and mobile networks.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompGuardInterceptor guard;
    private final ThreadPoolTaskScheduler scheduler;

    public WebSocketConfig(StompGuardInterceptor guard,
                           @Qualifier("brokerHeartbeatScheduler") ThreadPoolTaskScheduler scheduler) {
        this.guard = guard;
        this.scheduler = scheduler;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue")
                .setHeartbeatValue(new long[]{10_000, 10_000})
                .setTaskScheduler(scheduler);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/live")
                .setAllowedOriginPatterns("*")
                .withSockJS()
                .setSuppressCors(true);   // the gateway already adds the CORS headers
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(guard);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(128 * 1024)      // a call offer (SDP) is a few KB
                .setSendBufferSizeLimit(512 * 1024)
                .setSendTimeLimit(20_000);
    }
}
