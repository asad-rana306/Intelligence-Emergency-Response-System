package com.iers.realtime.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.realtime.dto.KafkaEvent;
import com.iers.realtime.service.FanOutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Reads the crash-events topic (IoT service). */
@Component
public class CrashEventListener {

    private static final Logger log = LoggerFactory.getLogger(CrashEventListener.class);

    private final FanOutService fanOut;
    private final ObjectMapper mapper;

    public CrashEventListener(FanOutService fanOut, ObjectMapper mapper) {
        this.fanOut = fanOut;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "${realtime.kafka.crash-topic:crash-events}",
                   groupId = "${realtime.kafka.group-id:realtime-group}")
    public void consume(String payload) {
        try {
            KafkaEvent event = KafkaEvent.parse(mapper, payload);
            log.info("crash-events: type={} crashEventId={}", event.eventType(), event.crashEventId());
            fanOut.handle(event);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("Unreadable crash event ignored: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Error handling crash event: {}", e.getMessage(), e);
        }
    }
}
