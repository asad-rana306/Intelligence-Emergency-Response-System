package com.iers.iot.service;

import com.iers.iot.dto.ml.MlPredictionResponse;
import com.iers.iot.dto.request.CrashPayloadRequest;
import com.iers.iot.dto.response.CrashAckResponse;
import com.iers.iot.entity.CrashEvent;
import com.iers.iot.entity.DevicePairing;
import com.iers.iot.entity.enums.CrashEventStatus;
import com.iers.iot.entity.enums.CrashSource;
import com.iers.iot.exception.CrashEventNotFoundException;
import com.iers.iot.repository.CrashEventRepository;
import com.iers.iot.repository.DevicePairingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CrashIngestionServiceTest {

    @Mock private CrashEventRepository crashEventRepository;
    @Mock private DevicePairingRepository pairingRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private CrashTimerService timerService;
    @Mock private MlServiceClient mlServiceClient;
    @Mock private KafkaProducerService kafkaProducer;

    @InjectMocks private CrashIngestionService service;

    private CrashPayloadRequest crashRequest;
    private DevicePairing testPairing;

    @BeforeEach
    void setUp() {
        crashRequest = CrashPayloadRequest.builder()
                .gForce(8.5).rolloverAngle(45.0).speed(120.0)
                .gpsLat(37.7749).gpsLng(-122.4194)
                .timestamp(System.currentTimeMillis())
                .build();

        testPairing = DevicePairing.builder()
                .id(UUID.randomUUID())
                .deviceId("DEV-001")
                .userId(UUID.randomUUID())
                .driverName("John Doe")
                .driverPhone("+14155551234")
                .build();
    }

    // ══════════════════ HTTP INGESTION ══════════════════

    @Test
    @DisplayName("ingestHttpCrash — success, saves event and starts timer")
    void ingestHttp_success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true); // Not a duplicate
        when(pairingRepository.findByDeviceId("DEV-001")).thenReturn(Optional.of(testPairing));
        when(crashEventRepository.save(any(CrashEvent.class))).thenAnswer(inv -> {
            CrashEvent e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        CrashAckResponse response = service.ingestHttpCrash("DEV-001", crashRequest);

        assertThat(response.getStatus()).isEqualTo("RECEIVED");
        assertThat(response.getCrashEventId()).isNotNull();
        verify(timerService).startCancellationWindow(any(UUID.class), any(Runnable.class));
    }

    @Test
    @DisplayName("ingestHttpCrash — duplicate payload returns DUPLICATE status")
    void ingestHttp_duplicate() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false); // Already exists → duplicate

        CrashAckResponse response = service.ingestHttpCrash("DEV-001", crashRequest);

        assertThat(response.getStatus()).isEqualTo("DUPLICATE");
        verify(crashEventRepository, never()).save(any());
        verify(timerService, never()).startCancellationWindow(any(), any());
    }

    @Test
    @DisplayName("ingestHttpCrash — unpaired device still ingests with null driver info")
    void ingestHttp_unpairedDevice() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(pairingRepository.findByDeviceId("UNKNOWN-DEV")).thenReturn(Optional.empty());
        when(crashEventRepository.save(any())).thenAnswer(inv -> {
            CrashEvent e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        CrashAckResponse response = service.ingestHttpCrash("UNKNOWN-DEV", crashRequest);

        assertThat(response.getStatus()).isEqualTo("RECEIVED");
        ArgumentCaptor<CrashEvent> captor = ArgumentCaptor.forClass(CrashEvent.class);
        verify(crashEventRepository).save(captor.capture());
        assertThat(captor.getValue().getDriverId()).isNull();
    }

    // ══════════════════ SMS INGESTION ══════════════════

    @Test
    @DisplayName("ingestSmsCrash — bypasses timer and goes directly to ML + Kafka")
    void ingestSms_success() {
        when(pairingRepository.findByDeviceId("DEV-001")).thenReturn(Optional.of(testPairing));
        when(crashEventRepository.save(any())).thenAnswer(inv -> {
            CrashEvent e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(crashEventRepository.findById(any())).thenAnswer(inv -> {
            CrashEvent e = CrashEvent.builder()
                    .id(inv.getArgument(0)).deviceId("DEV-001")
                    .driverId(testPairing.getUserId()).driverName("John Doe")
                    .driverPhone("+14155551234")
                    .gForce(8.5).rolloverAngle(45.0).speed(120.0)
                    .gpsLat(37.7749).gpsLng(-122.4194)
                    .source(CrashSource.SMS).status(CrashEventStatus.RECEIVED)
                    .build();
            return Optional.of(e);
        });
        when(mlServiceClient.predict(any())).thenReturn(
                MlPredictionResponse.builder().severe(true).priorityScore(4).severity("HIGH").build());

        CrashAckResponse response = service.ingestSmsCrash("DEV-001", 8.5, 45.0, 120.0, 37.77, -122.41);

        assertThat(response.getStatus()).isEqualTo("CONFIRMED");
        verify(timerService, never()).startCancellationWindow(any(), any()); // No timer for SMS
        verify(kafkaProducer).publishCrashEvent(any()); // Published to Kafka
    }

    // ══════════════════ CANCEL (within window) ══════════════════

    @Test
    @DisplayName("cancelCrash — success within window")
    void cancel_success() {
        UUID eventId = UUID.randomUUID();
        CrashEvent event = CrashEvent.builder()
                .id(eventId).status(CrashEventStatus.RECEIVED).build();

        when(crashEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(timerService.cancelTimer(eventId)).thenReturn(true);
        when(crashEventRepository.save(any())).thenReturn(event);

        CrashAckResponse response = service.cancelCrash(eventId);

        assertThat(response.getStatus()).isEqualTo("CANCELLED");
        assertThat(event.getStatus()).isEqualTo(CrashEventStatus.CANCELLED);
    }

    @Test
    @DisplayName("cancelCrash — already cancelled returns ALREADY_CANCELLED")
    void cancel_alreadyCancelled() {
        UUID eventId = UUID.randomUUID();
        CrashEvent event = CrashEvent.builder()
                .id(eventId).status(CrashEventStatus.CANCELLED).build();

        when(crashEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        CrashAckResponse response = service.cancelCrash(eventId);

        assertThat(response.getStatus()).isEqualTo("ALREADY_CANCELLED");
    }

    @Test
    @DisplayName("cancelCrash — throws when event not found")
    void cancel_notFound() {
        UUID eventId = UUID.randomUUID();
        when(crashEventRepository.findById(eventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelCrash(eventId))
                .isInstanceOf(CrashEventNotFoundException.class);
    }

    // ══════════════════ LATE CANCEL ══════════════════

    @Test
    @DisplayName("lateCancelCrash — publishes CRASH_CANCELLED to Kafka")
    void lateCancel_success() {
        UUID eventId = UUID.randomUUID();
        CrashEvent event = CrashEvent.builder()
                .id(eventId).deviceId("DEV-001")
                .driverId(UUID.randomUUID()).driverName("John")
                .driverPhone("+1234").status(CrashEventStatus.CONFIRMED).build();

        when(crashEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(crashEventRepository.save(any())).thenReturn(event);

        CrashAckResponse response = service.lateCancelCrash(eventId);

        assertThat(response.getStatus()).isEqualTo("LATE_CANCELLED");
        assertThat(event.getStatus()).isEqualTo(CrashEventStatus.LATE_CANCELLED);
        verify(kafkaProducer).publishCrashEvent(argThat(msg ->
                "CRASH_CANCELLED".equals(msg.getEventType())
                        && "LATE_CANCEL".equals(msg.getReason())));
    }

    @Test
    @DisplayName("lateCancelCrash — already cancelled returns ALREADY_CANCELLED")
    void lateCancel_alreadyCancelled() {
        UUID eventId = UUID.randomUUID();
        CrashEvent event = CrashEvent.builder()
                .id(eventId).status(CrashEventStatus.LATE_CANCELLED).build();

        when(crashEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        CrashAckResponse response = service.lateCancelCrash(eventId);

        assertThat(response.getStatus()).isEqualTo("ALREADY_CANCELLED");
    }

    // ══════════════════ TIMER EXPIRY (ML + Kafka) ══════════════════

    @Test
    @DisplayName("onCancellationWindowExpired — calls ML and publishes CRASH_DETECTED when severe")
    void timerExpiry_severe_publishes() {
        UUID eventId = UUID.randomUUID();
        CrashEvent event = CrashEvent.builder()
                .id(eventId).deviceId("DEV-001").driverId(UUID.randomUUID())
                .driverName("John").driverPhone("+1234")
                .gForce(9.0).rolloverAngle(60.0).speed(140.0)
                .gpsLat(37.7).gpsLng(-122.4)
                .source(CrashSource.HTTP).status(CrashEventStatus.RECEIVED).build();

        when(crashEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(crashEventRepository.save(any())).thenReturn(event);
        when(mlServiceClient.predict(any())).thenReturn(
                MlPredictionResponse.builder().severe(true).priorityScore(5).severity("CRITICAL").build());

        service.onCancellationWindowExpired(eventId);

        assertThat(event.getStatus()).isEqualTo(CrashEventStatus.CONFIRMED);
        assertThat(event.getPriorityScore()).isEqualTo(5);
        verify(kafkaProducer).publishCrashEvent(argThat(msg ->
                "CRASH_DETECTED".equals(msg.getEventType()) && msg.getPriorityScore() == 5));
    }

    @Test
    @DisplayName("onCancellationWindowExpired — ML says not severe, no Kafka publish")
    void timerExpiry_notSevere_noPublish() {
        UUID eventId = UUID.randomUUID();
        CrashEvent event = CrashEvent.builder()
                .id(eventId).deviceId("DEV-001")
                .gForce(2.0).rolloverAngle(5.0).speed(30.0)
                .source(CrashSource.HTTP).status(CrashEventStatus.RECEIVED).build();

        when(crashEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(crashEventRepository.save(any())).thenReturn(event);
        when(mlServiceClient.predict(any())).thenReturn(
                MlPredictionResponse.builder().severe(false).priorityScore(1).severity("LOW").build());

        service.onCancellationWindowExpired(eventId);

        verify(kafkaProducer, never()).publishCrashEvent(any());
    }

    @Test
    @DisplayName("onCancellationWindowExpired — event already cancelled, does nothing")
    void timerExpiry_alreadyCancelled() {
        UUID eventId = UUID.randomUUID();
        CrashEvent event = CrashEvent.builder()
                .id(eventId).status(CrashEventStatus.CANCELLED).build();

        when(crashEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        service.onCancellationWindowExpired(eventId);

        verify(mlServiceClient, never()).predict(any());
        verify(kafkaProducer, never()).publishCrashEvent(any());
    }

    // ══════════════════ IDEMPOTENCY ══════════════════

    @Test
    @DisplayName("isDuplicate — first call returns false (not duplicate)")
    void isDuplicate_firstCall() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        boolean result = service.isDuplicate("DEV-001", 1723300000000L);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("isDuplicate — second call returns true (duplicate)")
    void isDuplicate_secondCall() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        boolean result = service.isDuplicate("DEV-001", 1723300000000L);

        assertThat(result).isTrue();
    }
}
