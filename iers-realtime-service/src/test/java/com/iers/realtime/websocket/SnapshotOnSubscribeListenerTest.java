package com.iers.realtime.websocket;

import com.iers.realtime.service.SnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SnapshotOnSubscribeListenerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-29T18:30:12Z"), ZoneOffset.UTC);
    private SnapshotService snapshots;
    private TaskScheduler scheduler;
    private SnapshotOnSubscribeListener listener;

    @BeforeEach
    void setUp() {
        snapshots = mock(SnapshotService.class);
        scheduler = mock(TaskScheduler.class);
        listener = new SnapshotOnSubscribeListener(snapshots, scheduler, clock, 300);
    }

    private SessionSubscribeEvent subscribe(String destination, Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSessionId("s1");
        accessor.setSubscriptionId("sub-0");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionSubscribeEvent(this, message, user);
    }

    @Test
    @DisplayName("Subscribing to /user/queue/events sends the SNAPSHOT a moment later")
    void sendsSnapshot() {
        listener.onSubscribe(subscribe("/user/queue/events", new StompUser("u1")));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(task.capture(), org.mockito.ArgumentMatchers.eq(Instant.parse("2026-10-29T18:30:12.300Z")));
        verify(snapshots, never()).send(any());

        task.getValue().run();
        verify(snapshots).send("u1");
    }

    @Test
    @DisplayName("Subscribing to the call queue sends no SNAPSHOT")
    void callQueueIgnored() {
        listener.onSubscribe(subscribe("/user/queue/call", new StompUser("u1")));
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("A subscription with no user is ignored")
    void noUser() {
        listener.onSubscribe(subscribe("/user/queue/events", null));
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }
}
