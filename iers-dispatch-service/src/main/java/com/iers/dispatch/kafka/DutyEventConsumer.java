package com.iers.dispatch.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.dispatch.dto.kafka.DutyEventMessage;
import com.iers.dispatch.service.NotificationService;
import com.iers.dispatch.service.SpatialQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Reads Auth's duty events (topic duty-events).
 *
 *  - Any change to OFF_DUTY: the responder leaves Dispatch's pool at once (the 15-second sync would
 *    do it too, this is just faster).
 *  - DUTY_EXPIRED (shift over / phone silent): the responder also gets the push "You are now OFF duty",
 *    which the app turns into a sound and an "Go on duty again" action.
 *
 * Events are best effort on Auth's side. If one is lost, the sync still removes the responder; only the
 * push is missed (the app also knows its own shift end).
 *
 * Switch off with dispatch.kafka.duty-listener-enabled=false.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dispatch.kafka", name = "duty-listener-enabled", havingValue = "true", matchIfMissing = true)
public class DutyEventConsumer {

    static final String OFF_DUTY = "OFF_DUTY";

    private final ObjectMapper objectMapper;
    private final SpatialQueryService spatialQueryService;
    private final NotificationService notificationService;

    @KafkaListener(topics = "${dispatch.kafka.duty-topic:duty-events}",
            groupId = "${dispatch.kafka.group-id:dispatch-group}")
    public void consume(String payload) {
        try {
            DutyEventMessage event = objectMapper.readValue(payload, DutyEventMessage.class);
            if (event.getType() == null || event.getUserId() == null) {
                log.warn("Duty event without type or userId ignored");
                return;
            }
            UUID userId = UUID.fromString(event.getUserId());
            boolean off = OFF_DUTY.equals(event.getStatus());

            switch (event.getType()) {
                case DutyEventMessage.DUTY_EXPIRED -> {
                    log.info("Duty expired for responder {} (reason={})", userId, event.getReason());
                    spatialQueryService.markResponderOffDuty(userId);
                    notificationService.dutyExpired(userId, event.getReason());
                }
                case DutyEventMessage.DUTY_CHANGED -> {
                    if (off) {
                        spatialQueryService.markResponderOffDuty(userId);
                    }
                    // ON_DUTY: the next sync adds the responder (it needs the position from Auth).
                }
                default -> log.debug("Unknown duty event type {} ignored", event.getType());
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("Bad duty event ignored: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Error processing duty event: {}", e.getMessage(), e);
        }
    }
}
