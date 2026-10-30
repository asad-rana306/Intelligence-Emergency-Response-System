package com.iers.realtime.service;

import com.iers.realtime.dto.CallMessage;
import com.iers.realtime.dto.LiveEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LivePublisherTest {

    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final LivePublisher publisher = new LivePublisher(template);

    @Test
    @DisplayName("Events go to the user's private events queue")
    void event() {
        LiveEvent event = new LiveEvent("SNAPSHOT", "e1", "2026-10-29T18:30:12Z", Map.of());
        publisher.sendEvent("u1", event);
        verify(template).convertAndSendToUser("u1", "/queue/events", event);
    }

    @Test
    @DisplayName("Call messages go to the user's private call queue")
    void call() {
        CallMessage message = new CallMessage("i1", "u2", "RING", Map.of());
        publisher.sendCall("u1", message);
        verify(template).convertAndSendToUser("u1", "/queue/call", message);
    }
}
