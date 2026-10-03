package com.iers.iot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaProducerServiceTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private KafkaProducerService service;

    @Test
    @DisplayName("publishCrashEvent — sends serialized message with correct key")
    void publish_success() {
        ReflectionTestUtils.setField(service, "crashEventsTopic", "crash-events");

        CrashEventMessage message = CrashEventMessage.builder()
                .eventType("CRASH_DETECTED")
                .crashEventId("event-123")
                .driverId("driver-456")
                .deviceId("DEV-001")
                .gpsLat(37.77).gpsLng(-122.41)
                .priorityScore(4)
                .timestamp(Instant.now())
                .build();

        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(new CompletableFuture<>());

        service.publishCrashEvent(message);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);

        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), valueCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo("crash-events");
        assertThat(keyCaptor.getValue()).isEqualTo("event-123");
        assertThat(valueCaptor.getValue()).contains("CRASH_DETECTED");
        assertThat(valueCaptor.getValue()).contains("driver-456");
    }

    @Test
    @DisplayName("publishCrashEvent — CRASH_CANCELLED includes reason")
    void publish_cancelled() {
        ReflectionTestUtils.setField(service, "crashEventsTopic", "crash-events");

        CrashEventMessage message = CrashEventMessage.builder()
                .eventType("CRASH_CANCELLED")
                .crashEventId("event-789")
                .reason("LATE_CANCEL")
                .timestamp(Instant.now())
                .build();

        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(new CompletableFuture<>());

        service.publishCrashEvent(message);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), anyString(), valueCaptor.capture());

        assertThat(valueCaptor.getValue()).contains("CRASH_CANCELLED");
        assertThat(valueCaptor.getValue()).contains("LATE_CANCEL");
    }
}
