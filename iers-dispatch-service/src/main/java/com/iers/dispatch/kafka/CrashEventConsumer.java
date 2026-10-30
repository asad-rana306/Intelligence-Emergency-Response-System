package com.iers.dispatch.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.dispatch.dto.kafka.CrashEventMessage;
import com.iers.dispatch.service.DispatchOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CrashEventConsumer {

    private final DispatchOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${dispatch.kafka.topic:crash-events}",
            groupId = "${dispatch.kafka.group-id:dispatch-group}")
    public void consume(String payload) {
        try {
            CrashEventMessage message = objectMapper.readValue(payload, CrashEventMessage.class);
            log.info("Kafka event received: type={}, crashEventId={}",
                    message.getEventType(), message.getCrashEventId());

            if (message.getEventType() == null) {
                log.warn("Kafka event without eventType ignored");
                return;
            }

            switch (message.getEventType()) {
                case "CRASH_DETECTED" -> orchestrator.handleCrashDetected(message);
                case "CRASH_CANCELLED" -> orchestrator.handleCrashCancelled(message);
                // The 10-second window opened or closed with no dispatch. The Realtime service
                // cares about these. Dispatch has nothing to do.
                case "CRASH_PENDING", "CRASH_DISMISSED" ->
                        log.debug("Ignoring {} (not for Dispatch)", message.getEventType());
                default -> log.warn("Unknown event type: {}", message.getEventType());
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize Kafka message: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Error processing Kafka event: {}", e.getMessage(), e);
        }
    }
}
