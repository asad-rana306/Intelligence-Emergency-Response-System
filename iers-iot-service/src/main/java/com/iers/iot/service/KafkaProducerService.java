package com.iers.iot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.iot.dto.kafka.CrashEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaProducerService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${iot.kafka.topic:crash-events}")
    private String crashEventsTopic;

    /**
     * Publish a crash event message to Kafka.
     * Key = crashEventId (ensures ordering per incident within a partition).
     */
    public void publishCrashEvent(CrashEventMessage message) {
        try {
            String payload = objectMapper.writeValueAsString(message);

            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(crashEventsTopic, message.getCrashEventId(), payload);

            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish {} for crashEventId={}: {}",
                            message.getEventType(), message.getCrashEventId(), ex.getMessage());
                } else {
                    log.info("Published {} to topic={}, partition={}, offset={}, crashEventId={}",
                            message.getEventType(),
                            result.getRecordMetadata().topic(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset(),
                            message.getCrashEventId());
                }
            });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize crash event message: {}", e.getMessage());
            throw new RuntimeException("Kafka serialization failed", e);
        }
    }
}
