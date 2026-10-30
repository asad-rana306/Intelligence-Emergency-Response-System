package com.iers.realtime.service;

import com.iers.realtime.dto.KafkaEvent;
import com.iers.realtime.dto.LiveEvent;
import com.iers.realtime.store.LiveStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static com.iers.realtime.dto.EventTypes.*;
import static com.iers.realtime.store.LiveStateStore.CRASH;
import static com.iers.realtime.store.LiveStateStore.INCIDENT;
import static com.iers.realtime.store.LiveStateStore.OFFER;

/**
 * The fan-out rules (contract: "Live messages to phones").
 * One Kafka event in, then for each person it is meant for:
 *   1. the person's catch-up snapshot is updated, and
 *   2. the live message is pushed to that person's private queue.
 *
 * Who gets what:
 *   CRASH_PENDING / CRASH_DISMISSED ........ the driver
 *   INCIDENT_CREATED / ACCEPTED ............ the driver
 *   NO_RESPONDER_AVAILABLE ................. the driver
 *   INCIDENT_OFFERED / OFFER_EXPIRED ....... the offered responder
 *   INCIDENT_STAND_DOWN / BYSTANDER_ASSISTING  the responder
 *   INCIDENT_STATUS ........................ the driver and the responder
 *   CRASH_DETECTED / CRASH_CANCELLED ....... nobody (they only tidy the snapshot)
 *
 * Two safety rules:
 *   - A broken snapshot store (Redis down) never stops a live push.
 *   - An old event (older than max-push-age) still updates the snapshot but is not pushed,
 *     so a restart does not replay ancient news to phones.
 */
@Service
public class FanOutService {

    private static final Logger log = LoggerFactory.getLogger(FanOutService.class);

    private final LivePublisher publisher;
    private final LiveStateStore store;
    private final Clock clock;
    private final long maxPushAgeSeconds;

    public FanOutService(LivePublisher publisher,
                         LiveStateStore store,
                         Clock clock,
                         @Value("${realtime.max-push-age-seconds:120}") long maxPushAgeSeconds) {
        this.publisher = publisher;
        this.store = store;
        this.clock = clock;
        this.maxPushAgeSeconds = maxPushAgeSeconds;
    }

    public void handle(KafkaEvent e) {
        if (e == null || e.eventType() == null) {
            log.warn("Event without eventType ignored");
            return;
        }
        boolean fresh = isFresh(e);
        switch (e.eventType()) {
            case CRASH_PENDING -> onCrashPending(e, fresh);
            case CRASH_DISMISSED -> onCrashDismissed(e, fresh);
            case CRASH_DETECTED -> onCrashDetected(e);
            case CRASH_CANCELLED -> onCrashCancelled(e);
            case INCIDENT_CREATED -> onIncidentCreated(e, fresh);
            case INCIDENT_OFFERED -> onOffered(e, fresh);
            case INCIDENT_OFFER_EXPIRED -> onOfferExpired(e, fresh);
            case INCIDENT_ACCEPTED -> onAccepted(e, fresh);
            case INCIDENT_STATUS -> onStatus(e, fresh);
            case INCIDENT_STAND_DOWN -> onStandDown(e, fresh);
            case BYSTANDER_ASSISTING -> onBystander(e, fresh);
            case NO_RESPONDER_AVAILABLE -> onNoResponder(e, fresh);
            default -> log.debug("Ignoring event type {}", e.eventType());
        }
    }

    // ───────────────────────────── crash-events ─────────────────────────────

    private void onCrashPending(KafkaEvent e, boolean fresh) {
        String driver = e.driverId();
        Map<String, Object> data = map(
                "crashEventId", e.crashEventId(),
                "windowEndsAt", e.windowEndsAt() != null ? e.windowEndsAt().toString() : null,
                "source", e.source());
        if (driver != null) {
            state(() -> store.putSnapshot(driver, CRASH, data));
        }
        push(fresh, driver, e, data);
    }

    private void onCrashDismissed(KafkaEvent e, boolean fresh) {
        String driver = e.driverId();
        Map<String, Object> data = map("crashEventId", e.crashEventId(), "reason", e.reason());
        if (driver != null) {
            state(() -> store.removeSnapshot(driver, CRASH));
        }
        push(fresh, driver, e, data);
    }

    /** The window is over and the crash was severe. The incident arrives as INCIDENT_CREATED. */
    private void onCrashDetected(KafkaEvent e) {
        String driver = e.driverId();
        if (driver != null) {
            state(() -> store.removeSnapshot(driver, CRASH));
        }
    }

    /** Late cancel: the driver's incident is over. */
    private void onCrashCancelled(KafkaEvent e) {
        String driver = e.driverId();
        if (driver == null) {
            return;
        }
        state(() -> {
            store.removeSnapshot(driver, CRASH);
            Map<String, Object> incident = store.loadSnapshot(driver).get(INCIDENT);
            if (incident != null && e.crashEventId() != null
                    && e.crashEventId().equals(str(incident.get("crashEventId")))) {
                store.removeSnapshot(driver, INCIDENT);
                String incidentId = str(incident.get("incidentId"));
                if (incidentId != null) {
                    store.removeLink(incidentId);
                }
            }
        });
    }

    // ──────────────────────────── incident-events ────────────────────────────

    private void onIncidentCreated(KafkaEvent e, boolean fresh) {
        String driver = e.driverId();
        String incidentId = incidentIdOf(e);
        String crashEventId = first(str(e.data().get("crashEventId")), e.crashEventId());
        Map<String, Object> data = map("incidentId", incidentId, "crashEventId", crashEventId);
        if (driver != null && incidentId != null) {
            state(() -> {
                store.putSnapshot(driver, INCIDENT,
                        map("incidentId", incidentId, "crashEventId", crashEventId, "status", "DISPATCHING"));
                store.removeSnapshot(driver, CRASH);
                store.putLink(incidentId, link("driverId", driver, "status", "DISPATCHING"));
            });
        }
        push(fresh, driver, e, data);
    }

    private void onOffered(KafkaEvent e, boolean fresh) {
        String responder = e.responderId();
        String incidentId = incidentIdOf(e);
        Map<String, Object> data = new LinkedHashMap<>(e.data());
        data.putIfAbsent("incidentId", incidentId);
        if (responder != null) {
            state(() -> store.putSnapshot(responder, OFFER, data));
        }
        push(fresh, responder, e, data);
    }

    private void onOfferExpired(KafkaEvent e, boolean fresh) {
        String responder = e.responderId();
        String incidentId = incidentIdOf(e);
        if (responder != null) {
            state(() -> removeIfSame(responder, OFFER, incidentId));
        }
        push(fresh, responder, e, map("incidentId", incidentId));
    }

    private void onAccepted(KafkaEvent e, boolean fresh) {
        String driver = e.driverId();
        String responder = e.responderId();
        String incidentId = incidentIdOf(e);
        Object responderInfo = e.data().get("responder");
        Map<String, Object> data = map("incidentId", incidentId, "responder", responderInfo);
        state(() -> {
            if (driver != null) {
                store.putSnapshot(driver, INCIDENT, map(
                        "incidentId", incidentId, "crashEventId", e.crashEventId(),
                        "status", "ACCEPTED", "responder", responderInfo));
            }
            if (responder != null) {
                removeIfSame(responder, OFFER, incidentId);
                store.putSnapshot(responder, INCIDENT, map(
                        "incidentId", incidentId, "crashEventId", e.crashEventId(), "status", "ACCEPTED"));
            }
            if (incidentId != null) {
                store.putLink(incidentId, link("driverId", driver, "responderId", responder, "status", "ACCEPTED"));
            }
        });
        push(fresh, driver, e, data);
    }

    private void onStatus(KafkaEvent e, boolean fresh) {
        String driver = e.driverId();
        String responder = e.responderId();
        String incidentId = incidentIdOf(e);
        String status = str(e.data().get("status"));
        boolean finished = "RESOLVED".equals(status);
        state(() -> {
            for (String user : people(driver, responder)) {
                if (finished) {
                    removeIfSame(user, INCIDENT, incidentId);
                } else {
                    upsertIncident(user, e, incidentId, m -> m.put("status", status));
                }
            }
            if (incidentId != null) {
                if (finished) {
                    store.removeLink(incidentId);
                } else {
                    store.putLink(incidentId, link("driverId", driver, "responderId", responder, "status", status));
                }
            }
        });
        Map<String, Object> data = map("incidentId", incidentId, "status", status);
        push(fresh, driver, e, data);
        push(fresh, responder, e, data);
    }

    /** The driver cancelled after dispatch: the whole incident is over. */
    private void onStandDown(KafkaEvent e, boolean fresh) {
        String driver = e.driverId();
        String responder = e.responderId();
        String incidentId = incidentIdOf(e);
        state(() -> {
            if (responder != null) {
                removeIfSame(responder, OFFER, incidentId);
                removeIfSame(responder, INCIDENT, incidentId);
            }
            if (driver != null) {
                removeIfSame(driver, INCIDENT, incidentId);
            }
            if (incidentId != null) {
                store.removeLink(incidentId);
            }
        });
        push(fresh, responder, e, map("incidentId", incidentId, "reason", str(e.data().get("reason"))));
    }

    private void onBystander(KafkaEvent e, boolean fresh) {
        push(fresh, e.responderId(), e, map("incidentId", incidentIdOf(e)));
    }

    private void onNoResponder(KafkaEvent e, boolean fresh) {
        String driver = e.driverId();
        String incidentId = incidentIdOf(e);
        if (driver != null) {
            state(() -> upsertIncident(driver, e, incidentId, m -> m.put("noResponderAvailable", true)));
        }
        push(fresh, driver, e, map("incidentId", incidentId));
    }

    // ───────────────────────────────── helpers ─────────────────────────────────

    private boolean isFresh(KafkaEvent e) {
        if (e.timestamp() == null) {
            return true;
        }
        return Duration.between(e.timestamp(), clock.instant()).getSeconds() <= maxPushAgeSeconds;
    }

    private void push(boolean fresh, String userId, KafkaEvent e, Map<String, Object> data) {
        if (userId == null) {
            log.debug("{}: nobody to tell (no user id on the event)", e.eventType());
            return;
        }
        if (!fresh) {
            log.info("{} for user {} is too old to push; snapshot only", e.eventType(), userId);
            return;
        }
        LiveEvent live = new LiveEvent(e.eventType(), eventId(e, userId), clock.instant().toString(), data);
        try {
            publisher.sendEvent(userId, live);
            log.info("Pushed {} to user {}", e.eventType(), userId);
        } catch (RuntimeException ex) {
            log.warn("Could not push {} to user {}: {}", e.eventType(), userId, ex.getMessage());
        }
    }

    /** Same Kafka event + same person = same eventId, so a phone can drop a repeat. */
    static String eventId(KafkaEvent e, String userId) {
        String ref = e.incidentId() != null ? e.incidentId() : e.crashEventId();
        String seed = e.eventType() + "|" + ref + "|" + e.timestamp() + "|" + userId;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Snapshot work must never stop the live push. */
    private void state(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException ex) {
            log.warn("Snapshot update failed (live push is not affected): {}", ex.getMessage());
        }
    }

    private void removeIfSame(String userId, String slot, String incidentId) {
        Map<String, Object> current = store.loadSnapshot(userId).get(slot);
        if (current == null) {
            return;
        }
        if (incidentId == null || incidentId.equals(str(current.get("incidentId")))) {
            store.removeSnapshot(userId, slot);
        }
    }

    /** Changes the user's incident slot if it is this incident, else starts a fresh one. */
    private void upsertIncident(String userId, KafkaEvent e, String incidentId, Consumer<Map<String, Object>> change) {
        Map<String, Object> current = store.loadSnapshot(userId).get(INCIDENT);
        Map<String, Object> next;
        if (current != null && incidentId != null && incidentId.equals(str(current.get("incidentId")))) {
            next = new LinkedHashMap<>(current);
        } else {
            next = map("incidentId", incidentId, "crashEventId", e.crashEventId());
        }
        change.accept(next);
        store.putSnapshot(userId, INCIDENT, next);
    }

    private static String incidentIdOf(KafkaEvent e) {
        return first(e.incidentId(), str(e.data().get("incidentId")));
    }

    private static List<String> people(String driver, String responder) {
        return java.util.stream.Stream.of(driver, responder).filter(java.util.Objects::nonNull).toList();
    }

    private static String first(String a, String b) {
        return a != null ? a : b;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** Key/value pairs into a map that allows null values (Map.of does not). */
    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return m;
    }

    /** Like map(), but left-out when the value is null (a Redis hash cannot hold null). */
    private static Map<String, String> link(String... keysAndValues) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            if (keysAndValues[i + 1] != null) {
                m.put(keysAndValues[i], keysAndValues[i + 1]);
            }
        }
        return m;
    }
}
