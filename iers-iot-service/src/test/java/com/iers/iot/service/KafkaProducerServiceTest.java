package com.iers.iot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iers.iot.dto.kafka.CrashEventMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaProducerServiceTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    // The real app's mapper knows about Instant; a plain new ObjectMapper() does not.
    @Spy private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks private KafkaProducerService service;

    private String publishAndCaptureValue(CrashEventMessage message, String expectedKey) {
        ReflectionTestUtils.setField(service, "crashEventsTopic", "crash-events");
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(new CompletableFuture<>());

        service.publishCrashEvent(message);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), valueCaptor.capture());
        assertThat(topicCaptor.getValue()).isEqualTo("crash-events");
        assertThat(keyCaptor.getValue()).isEqualTo(expectedKey);
        return valueCaptor.getValue();
    }

    @Test
    @DisplayName("publishCrashEvent — sends serialized message with correct key")
    void publish_success() {
        CrashEventMessage message = CrashEventMessage.builder()
                .eventType("CRASH_DETECTED")
                .crashEventId("event-123")
                .driverId("driver-456")
                .deviceId("DEV-001")
                .gpsLat(37.77).gpsLng(-122.41)
                .priorityScore(4)
                .timestamp(Instant.now())
                .build();

        String value = publishAndCaptureValue(message, "event-123");

        assertThat(value).contains("CRASH_DETECTED");
        assertThat(value).contains("driver-456");
    }

    @Test
    @DisplayName("publishCrashEvent — CRASH_CANCELLED includes reason")
    void publish_cancelled() {
        CrashEventMessage message = CrashEventMessage.builder()
                .eventType("CRASH_CANCELLED")
                .crashEventId("event-789")
                .reason("LATE_CANCEL")
                .timestamp(Instant.now())
                .build();

        String value = publishAndCaptureValue(message, "event-789");

        assertThat(value).contains("CRASH_CANCELLED");
        assertThat(value).contains("LATE_CANCEL");
    }

    @Test
    @DisplayName("publishCrashEvent — CRASH_PENDING carries windowEndsAt")
    void publish_pending() {
        CrashEventMessage message = CrashEventMessage.builder()
                .eventType("CRASH_PENDING")
                .crashEventId("event-1")
                .driverId("driver-1")
                .deviceId("DEV-001")
                .source("HTTP")
                .windowEndsAt(Instant.parse("2026-10-29T18:30:10Z"))
                .timestamp(Instant.parse("2026-10-29T18:30:00Z"))
                .build();

        String value = publishAndCaptureValue(message, "event-1");

        assertThat(value).contains("CRASH_PENDING");
        assertThat(value).contains("windowEndsAt");
        assertThat(value).contains("2026-10-29T18:30:10Z");
    }

    @Test
    @DisplayName("publishCrashEvent — CRASH_DISMISSED carries the reason")
    void publish_dismissed() {
        CrashEventMessage message = CrashEventMessage.builder()
                .eventType("CRASH_DISMISSED")
                .crashEventId("event-2")
                .driverId("driver-1")
                .reason("ML_NOT_SEVERE")
                .timestamp(Instant.now())
                .build();

        String value = publishAndCaptureValue(message, "event-2");

        assertThat(value).contains("CRASH_DISMISSED");
        assertThat(value).contains("ML_NOT_SEVERE");
    }
}
