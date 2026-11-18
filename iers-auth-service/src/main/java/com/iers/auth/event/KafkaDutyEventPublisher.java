package com.iers.auth.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.auth.config.DutyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends duty events to Kafka (plain JSON text). Used only when {@code auth.duty.events.enabled=true}.
 * The user id is the message key, so all events of one responder stay in order.
 *
 * Producer timeouts are short (see application.yml), so a Kafka outage can not freeze the duty sweep.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "auth.duty.events", name = "enabled", havingValue = "true")
public class KafkaDutyEventPublisher implements DutyEventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final DutyProperties props;

    @Override
    public void publish(DutyEvent event) {
        try {
            String json = mapper.writeValueAsString(event);
            kafka.send(props.events().topic(), event.userId(), json)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.error("Could not publish {} for {}: {}", event.type(), event.userId(), error.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.error("Could not publish {} for {}: {}", event.type(), event.userId(), e.getMessage());
        }
    }
}
