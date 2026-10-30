package com.iers.realtime.service;

import com.iers.realtime.dto.CallKind;
import com.iers.realtime.dto.CallMessage;
import com.iers.realtime.dto.LiveEvent;
import com.iers.realtime.store.LiveStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.iers.realtime.dto.EventTypes.CALL_ERROR;

/**
 * Call set-up relay (contract: "Call messages").
 * Passes RING / ACCEPT / DECLINE / OFFER / ANSWER / ICE / HANGUP between exactly two people:
 * the driver and the assigned responder of ONE incident. Nobody else, and the audio never comes here.
 *
 * Who the two people are comes from the Kafka events (the "link" in the store).
 * A call is allowed only while the incident is ACCEPTED, ARRIVED or EN_ROUTE_HOSPITAL.
 * When a message cannot be passed on, the sender gets a CALL_ERROR event with a reason.
 */
@Service
public class CallRelayService {

    private static final Logger log = LoggerFactory.getLogger(CallRelayService.class);

    /** Why a message was not passed on. */
    public enum Outcome { SENT, BAD_MESSAGE, INCIDENT_NOT_ACTIVE, NOT_PARTICIPANT, PEER_OFFLINE }

    private static final Set<String> CALL_ALLOWED_STATUS = Set.of("ACCEPTED", "ARRIVED", "EN_ROUTE_HOSPITAL");

    private final LiveStateStore store;
    private final LivePublisher publisher;
    private final SimpUserRegistry users;
    private final Clock clock;

    public CallRelayService(LiveStateStore store, LivePublisher publisher, SimpUserRegistry users, Clock clock) {
        this.store = store;
        this.publisher = publisher;
        this.users = users;
        this.clock = clock;
    }

    public Outcome relay(String senderId, String rawIncidentId, CallMessage message) {
        CallKind kind = message == null ? null : CallKind.parse(message.kind());
        String incidentId = normalise(rawIncidentId);
        if (kind == null || incidentId == null) {
            return refuse(senderId, rawIncidentId, kind, Outcome.BAD_MESSAGE);
        }

        Map<String, String> link;
        try {
            link = store.getLink(incidentId);
        } catch (RuntimeException e) {
            log.warn("Could not read the call link of incident {}: {}", incidentId, e.getMessage());
            link = Map.of();
        }
        if (link.isEmpty()) {
            return refuse(senderId, incidentId, kind, Outcome.INCIDENT_NOT_ACTIVE);
        }

        String driver = link.get("driverId");
        String responder = link.get("responderId");
        boolean senderIsDriver = senderId != null && senderId.equals(driver);
        boolean senderIsResponder = senderId != null && senderId.equals(responder);
        if (!senderIsDriver && !senderIsResponder) {
            return refuse(senderId, incidentId, kind, Outcome.NOT_PARTICIPANT);
        }
        if (driver == null || responder == null || !CALL_ALLOWED_STATUS.contains(link.get("status"))) {
            return refuse(senderId, incidentId, kind, Outcome.INCIDENT_NOT_ACTIVE);
        }

        String peer = senderIsDriver ? responder : driver;
        // A HANGUP is always sent (if the other phone is gone, nothing is lost). Anything else needs it online.
        if (kind != CallKind.HANGUP && users.getUser(peer) == null) {
            return refuse(senderId, incidentId, kind, Outcome.PEER_OFFLINE);
        }

        // "from" is always the real sender, whatever the phone wrote.
        publisher.sendCall(peer, new CallMessage(incidentId, senderId, kind.name(), message.payload()));
        log.info("Call {} relayed: incident={} {} -> {}", kind, incidentId, senderId, peer);
        return Outcome.SENT;
    }

    /** For a message that could not even be read (bad JSON). */
    public void rejectUnreadable(String senderId) {
        refuse(senderId, null, null, Outcome.BAD_MESSAGE);
    }

    private Outcome refuse(String senderId, String incidentId, CallKind kind, Outcome outcome) {
        log.info("Call message refused: sender={} incident={} reason={}", senderId, incidentId, outcome);
        if (senderId != null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("incidentId", incidentId);
            data.put("kind", kind != null ? kind.name() : null);
            data.put("reason", outcome.name());
            try {
                publisher.sendEvent(senderId,
                        new LiveEvent(CALL_ERROR, UUID.randomUUID().toString(), clock.instant().toString(), data));
            } catch (RuntimeException e) {
                log.warn("Could not send CALL_ERROR to {}: {}", senderId, e.getMessage());
            }
        }
        return outcome;
    }

    /** Lower-case UUID text, or null when it is not a UUID. */
    private static String normalise(String id) {
        if (id == null) {
            return null;
        }
        try {
            return UUID.fromString(id.trim()).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
