package com.iers.realtime.service;

import com.iers.realtime.dto.LiveEvent;
import com.iers.realtime.store.LiveStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.iers.realtime.dto.EventTypes.SNAPSHOT;
import static com.iers.realtime.store.LiveStateStore.CRASH;
import static com.iers.realtime.store.LiveStateStore.INCIDENT;
import static com.iers.realtime.store.LiveStateStore.OFFER;

/**
 * The SNAPSHOT a phone gets when it connects (and when it asks for one):
 *   data = { crash, offer, incident }, each the current state or null.
 *
 * A countdown that has already run out is sent as null, so a phone that reconnects late does
 * not show a dead countdown or a dead offer.
 * If the store is down the phone still gets a SNAPSHOT, with everything null.
 */
@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final LiveStateStore store;
    private final LivePublisher publisher;
    private final Clock clock;

    public SnapshotService(LiveStateStore store, LivePublisher publisher, Clock clock) {
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
    }

    public LiveEvent build(String userId) {
        Map<String, Map<String, Object>> saved;
        try {
            saved = store.loadSnapshot(userId);
        } catch (RuntimeException e) {
            log.warn("Could not read the snapshot of user {}: {}", userId, e.getMessage());
            saved = Map.of();
        }
        Instant now = clock.instant();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("crash", unlessOver(saved.get(CRASH), "windowEndsAt", now));
        data.put("offer", unlessOver(saved.get(OFFER), "dispatchExpiresAt", now));
        data.put("incident", saved.get(INCIDENT));
        return new LiveEvent(SNAPSHOT, UUID.randomUUID().toString(), now.toString(), data);
    }

    public void send(String userId) {
        try {
            publisher.sendEvent(userId, build(userId));
            log.info("Sent SNAPSHOT to user {}", userId);
        } catch (RuntimeException e) {
            log.warn("Could not send the SNAPSHOT to user {}: {}", userId, e.getMessage());
        }
    }

    /** The value, or null when its end time has passed. A value with no readable end time is kept. */
    private static Map<String, Object> unlessOver(Map<String, Object> value, String endField, Instant now) {
        if (value == null) {
            return null;
        }
        Object end = value.get(endField);
        if (end == null) {
            return value;
        }
        try {
            return Instant.parse(end.toString()).isBefore(now) ? null : value;
        } catch (DateTimeParseException e) {
            return value;
        }
    }
}
