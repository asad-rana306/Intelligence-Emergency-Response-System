package com.iers.dispatch.push;

import com.iers.dispatch.dto.kafka.IncidentEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Second listener on "incident-events" (separate consumer group from the SMS one).
 * Pushes a Firebase notification so the user hears it even with the app closed.
 *
 *  - INCIDENT_OFFERED → high-priority push to the OFFERED responder ("Accept now")
 *  - INCIDENT_ACCEPTED → info push to the driver ("A responder is on the way")
 *  - INCIDENT_STAND_DOWN → info push to the assigned responder ("Driver cancelled")
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentPushListener {

    private final PushService push;

    @KafkaListener(
        topics = "incident-events",
        groupId = "push-notification-service",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void onEvent(IncidentEventMessage evt) {
        if (evt == null || evt.getEventType() == null) return;
        try {
            switch (evt.getEventType()) {
                case IncidentEventMessage.INCIDENT_OFFERED -> handleOffered(evt);
                case IncidentEventMessage.INCIDENT_ACCEPTED -> handleAccepted(evt);
                case IncidentEventMessage.INCIDENT_STAND_DOWN -> handleStandDown(evt);
                default -> { /* ignore */ }
            }
        } catch (Exception e) {
            log.error("Push listener failed for {}: {}", evt.getIncidentId(), e.getMessage(), e);
        }
    }

    private void handleOffered(IncidentEventMessage evt) {
        if (evt.getResponderId() == null) return;
        Map<String, Object> d = evt.getData() == null ? Map.of() : evt.getData();
        Double lat = asDouble(d.get("lat"));
        Double lng = asDouble(d.get("lng"));
        if (lat == null || lng == null) return;
        String driverName = asString(d.get("driverName"));
        push.sendOffer(UUID.fromString(evt.getResponderId()),
                evt.getIncidentId(), lat, lng, driverName);
    }

    private void handleAccepted(IncidentEventMessage evt) {
        if (evt.getDriverId() == null) return;
        push.sendInfo(UUID.fromString(evt.getDriverId()),
                "A responder is on the way",
                "They should arrive shortly.",
                Map.of("type", "ACCEPTED", "incidentId", evt.getIncidentId()));
    }

    private void handleStandDown(IncidentEventMessage evt) {
        if (evt.getResponderId() == null) return;
        push.sendInfo(UUID.fromString(evt.getResponderId()),
                "Stand down",
                "The driver cancelled this incident.",
                Map.of("type", "STAND_DOWN", "incidentId", evt.getIncidentId()));
    }

    private static Double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) try { return Double.parseDouble(s); } catch (Exception ignored) { return null; }
        return null;
    }
    private static String asString(Object o) { return o == null ? null : o.toString(); }
}
