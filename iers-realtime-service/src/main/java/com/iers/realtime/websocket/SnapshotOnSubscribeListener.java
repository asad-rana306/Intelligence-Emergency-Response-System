package com.iers.realtime.websocket;

import com.iers.realtime.service.SnapshotService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;
import java.time.Clock;

/**
 * "On connect the server first sends one SNAPSHOT."
 * A phone can only receive after it subscribed, so the SNAPSHOT is sent right after the phone
 * subscribes to /user/queue/events. It is sent a moment later (snapshot-delay-ms) because the
 * broker registers the subscription a few milliseconds after the SUBSCRIBE frame arrives.
 * A phone can also ask again at any time by sending to /app/snapshot.
 */
@Component
public class SnapshotOnSubscribeListener {

    static final String EVENTS_DESTINATION = "/user/queue/events";

    private final SnapshotService snapshots;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final long delayMs;

    public SnapshotOnSubscribeListener(SnapshotService snapshots,
                                       @Qualifier("brokerHeartbeatScheduler") TaskScheduler scheduler,
                                       Clock clock,
                                       @Value("${realtime.snapshot-delay-ms:300}") long delayMs) {
        this.snapshots = snapshots;
        this.scheduler = scheduler;
        this.clock = clock;
        this.delayMs = delayMs;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        if (!EVENTS_DESTINATION.equals(accessor.getDestination())) {
            return;
        }
        Principal user = event.getUser();
        if (user == null) {
            return;
        }
        String userId = user.getName();
        scheduler.schedule(() -> snapshots.send(userId), clock.instant().plusMillis(delayMs));
    }
}
