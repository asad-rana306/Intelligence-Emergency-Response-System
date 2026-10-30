package com.iers.realtime.service;

import com.iers.realtime.dto.CallMessage;
import com.iers.realtime.dto.LiveEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * The only class that writes to a phone. Every message goes to ONE user's private queue
 * ("/user/queue/..."): the broker delivers it only to the sessions of that user.
 * Nobody else can subscribe to it (see StompGuardInterceptor).
 */
@Component
public class LivePublisher {

    public static final String EVENTS_QUEUE = "/queue/events";
    public static final String CALL_QUEUE = "/queue/call";

    private final SimpMessagingTemplate template;

    public LivePublisher(SimpMessagingTemplate template) {
        this.template = template;
    }

    public void sendEvent(String userId, LiveEvent event) {
        template.convertAndSendToUser(userId, EVENTS_QUEUE, event);
    }

    public void sendCall(String userId, CallMessage message) {
        template.convertAndSendToUser(userId, CALL_QUEUE, message);
    }
}
