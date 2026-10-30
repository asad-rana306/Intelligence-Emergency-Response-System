package com.iers.dispatch.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iers.dispatch.dto.kafka.CrashEventMessage;
import com.iers.dispatch.service.DispatchOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CrashEventConsumerTest {

    @Mock private DispatchOrchestrator orchestrator;
    @Spy private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks private CrashEventConsumer consumer;

    private String crashDetectedPayload;
    private String crashCancelledPayload;

    @BeforeEach
    void setUp() throws Exception {
        CrashEventMessage detected = CrashEventMessage.builder()
                .eventType("CRASH_DETECTED")
                .crashEventId("event-123").driverId("driver-456")
                .gpsLat(37.77).gpsLng(-122.41).priorityScore(4)
                .timestamp(Instant.now()).build();

        CrashEventMessage cancelled = CrashEventMessage.builder()
                .eventType("CRASH_CANCELLED")
                .crashEventId("event-789").reason("LATE_CANCEL")
                .timestamp(Instant.now()).build();

        crashDetectedPayload = objectMapper.writeValueAsString(detected);
        crashCancelledPayload = objectMapper.writeValueAsString(cancelled);
    }

    private String payloadOf(String type) throws Exception {
        return objectMapper.writeValueAsString(CrashEventMessage.builder()
                .eventType(type).crashEventId("xxx").build());
    }

    @Test
    @DisplayName("CRASH_DETECTED routes to handleCrashDetected")
    void consume_crashDetected() {
        consumer.consume(crashDetectedPayload);
        verify(orchestrator).handleCrashDetected(any(CrashEventMessage.class));
        verify(orchestrator, never()).handleCrashCancelled(any());
    }

    @Test
    @DisplayName("CRASH_CANCELLED routes to handleCrashCancelled")
    void consume_crashCancelled() {
        consumer.consume(crashCancelledPayload);
        verify(orchestrator).handleCrashCancelled(any(CrashEventMessage.class));
        verify(orchestrator, never()).handleCrashDetected(any());
    }

    @Test
    @DisplayName("CRASH_PENDING and CRASH_DISMISSED (Phase 2 events) are ignored quietly")
    void consume_phase2Events() throws Exception {
        consumer.consume(payloadOf("CRASH_PENDING"));
        consumer.consume(payloadOf("CRASH_DISMISSED"));
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("windowEndsAt on a CRASH_PENDING message is understood")
    void consume_windowEndsAtParsed() throws Exception {
        String json = "{\"eventType\":\"CRASH_PENDING\",\"crashEventId\":\"abc\","
                + "\"windowEndsAt\":\"2026-10-29T18:30:10Z\",\"timestamp\":\"2026-10-29T18:30:00Z\"}";
        CrashEventMessage parsed = objectMapper.readValue(json, CrashEventMessage.class);
        assertThat(parsed.getWindowEndsAt()).isEqualTo(Instant.parse("2026-10-29T18:30:10Z"));

        consumer.consume(json);
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("Malformed JSON does not crash the consumer")
    void consume_malformedJson() {
        consumer.consume("not valid json at all {{{");
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("A message without eventType is ignored")
    void consume_noEventType() {
        consumer.consume("{\"crashEventId\":\"xxx\"}");
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("Unknown event type is logged but ignored")
    void consume_unknownType() throws Exception {
        consumer.consume(payloadOf("SOME_FUTURE_EVENT"));
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("An error inside the orchestrator does not escape the consumer")
    void consume_orchestratorFails() {
        doThrow(new IllegalStateException("db down")).when(orchestrator).handleCrashDetected(any());
        assertThatCode(() -> consumer.consume(crashDetectedPayload)).doesNotThrowAnyException();
    }
}
