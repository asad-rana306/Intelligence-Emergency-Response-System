package com.iers.realtime.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.realtime.dto.KafkaEvent;
import com.iers.realtime.service.FanOutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Reads the incident-events topic (Dispatch service). */
@Component
public class IncidentEventListener {

    private static final Logger log = LoggerFactory.getLogger(IncidentEventListener.class);

    private final FanOutService fanOut;
    private final ObjectMapper mapper;

    public IncidentEventListener(FanOutService fanOut, ObjectMapper mapper) {
        this.fanOut = fanOut;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "${realtime.kafka.incident-topic:incident-events}",
                   groupId = "${realtime.kafka.group-id:realtime-group}")
    public void consume(String payload) {
        try {
            KafkaEvent event = KafkaEvent.parse(mapper, payload);
            log.info("incident-events: type={} incidentId={}", event.eventType(), event.incidentId());
            fanOut.handle(event);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("Unreadable incident event ignored: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Error handling incident event: {}", e.getMessage(), e);
        }
    }
}
