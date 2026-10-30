package com.iers.dispatch.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.dispatch.dto.kafka.IncidentEventMessage;
import com.iers.dispatch.entity.Incident;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dispatch tells the world what it did, on topic "incident-events" (key = incidentId, so all
 * events of one incident stay in order).
 *
 * Two safety rules:
 *  1. If a database transaction is open, the event is sent only AFTER it commits. A phone that
 *     gets INCIDENT_CREATED can then immediately ask the REST API and find the incident.
 *  2. Telling the world must never break the rescue. Any Kafka problem is only logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${dispatch.kafka.incident-topic:incident-events}")
    private String incidentTopic = "incident-events";

    /**
     * @param eventType   one of the IncidentEventMessage constants
     * @param incident    the incident this is about (ids are copied from it)
     * @param responderId the responder this event is about, or null
     * @param data        extra fields for this event type (may be null)
     */
    public void publish(String eventType, Incident incident, UUID responderId, Map<String, Object> data) {
        IncidentEventMessage message = IncidentEventMessage.builder()
                .eventType(eventType)
                .incidentId(incident.getId().toString())
                .crashEventId(incident.getCrashEventId() != null ? incident.getCrashEventId().toString() : null)
                .driverId(incident.getDriverId() != null ? incident.getDriverId().toString() : null)
                .responderId(responderId != null ? responderId.toString() : null)
                .timestamp(Instant.now().toString())
                .data(data)
                .build();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendSafely(message);
                }
            });
        } else {
            sendSafely(message);
        }
    }

    void sendSafely(IncidentEventMessage message) {
        try {
            String payload = objectMapper.writeValueAsString(message);
            kafkaTemplate.send(incidentTopic, message.getIncidentId(), payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish {} for incident={}: {}",
                                    message.getEventType(), message.getIncidentId(), ex.getMessage());
                        } else {
                            log.info("Published {} incident={} partition={} offset={}",
                                    message.getEventType(), message.getIncidentId(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (JsonProcessingException e) {
            log.error("Could not serialize {} for incident={}: {}",
                    message.getEventType(), message.getIncidentId(), e.getMessage());
        } catch (Exception e) {
            log.error("Could not publish {} for incident={}: {}",
                    message.getEventType(), message.getIncidentId(), e.getMessage());
        }
    }
}
