package com.iers.iot.service;

import com.iers.iot.dto.kafka.CrashEventMessage;
import com.iers.iot.dto.ml.MlPredictionResponse;
import com.iers.iot.dto.request.CrashPayloadRequest;
import com.iers.iot.dto.request.PhoneCrashRequest;
import com.iers.iot.dto.response.CrashAckResponse;
import com.iers.iot.entity.CrashEvent;
import com.iers.iot.entity.DevicePairing;
import com.iers.iot.entity.enums.CrashEventStatus;
import com.iers.iot.entity.enums.CrashSource;
import com.iers.iot.exception.CrashAccessDeniedException;
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
    private UUID driverId;

    @BeforeEach
    void setUp() {
        crashRequest = CrashPayloadRequest.builder()
                .gForce(8.5).rolloverAngle(45.0).speed(120.0)
                .gpsLat(37.7749).gpsLng(-122.4194)
                .timestamp(System.currentTimeMillis())
                .build();

        driverId = UUID.randomUUID();
        testPairing = DevicePairing.builder()
                .id(UUID.randomUUID())
                .deviceId("DEV-001")
                .userId(driverId)
                .driverName("John Doe")
                .driverPhone("+14155551234")
                .build();
    }

    // ── small helpers ──

    private void notADuplicate() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    private void saveAssignsId() {
        when(crashEventRepository.save(any(CrashEvent.class))).thenAnswer(inv -> {
            CrashEvent e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
    }

    private CrashEvent event(UUID id, CrashEventStatus status) {
        return CrashEvent.builder()
                .id(id).deviceId("DEV-001").driverId(driverId)
                .driverName("John Doe").driverPhone("+14155551234")
                .gForce(9.0).rolloverAngle(60.0).speed(140.0)
                .gpsLat(37.7).gpsLng(-122.4)
                .source(CrashSource.HTTP).status(status).build();
    }

    private CrashEvent eventWithDeadline(UUID id, CrashEventStatus status, Instant deadline) {
        return CrashEvent.builder()
                .id(id).deviceId("DEV-001").driverId(driverId)
                .driverName("John Doe").driverPhone("+14155551234")
                .gForce(9.0).rolloverAngle(60.0).speed(140.0)
                .gpsLat(37.7).gpsLng(-122.4)
                .source(CrashSource.HTTP).status(status).cancellationDeadline(deadline).build();
    }

    // ══════════════════ HTTP INGESTION ══════════════════

    @Test
    @DisplayName("ingestHttpCrash — saves, starts the timer, publishes CRASH_PENDING, returns windowEndsAt")
    void ingestHttp_success() {
        notADuplicate();
        when(pairingRepository.findByDeviceId("DEV-001")).thenReturn(Optional.of(testPairing));
        saveAssignsId();

        CrashAckResponse response = service.ingestHttpCrash("DEV-001", crashRequest);

        assertThat(response.getStatus()).isEqualTo("RECEIVED");
        assertThat(response.getCrashEventId()).isNotNull();
        assertThat(response.getServerTime()).isNotNull();
        Instant ends = Instant.parse(response.getWindowEndsAt());
        Instant server = Instant.parse(response.getServerTime());
        assertThat(Duration.between(server, ends)).isEqualTo(Duration.ofSeconds(10));
        verify(timerService).startCancellationWindow(any(UUID.class), any(Runnable.class));

        ArgumentCaptor<CrashEventMessage> msg = ArgumentCaptor.forClass(CrashEventMessage.class);
        verify(kafkaProducer).publishCrashEvent(msg.capture());
        assertThat(msg.getValue().getEventType()).isEqualTo("CRASH_PENDING");
        assertThat(msg.getValue().getDriverId()).isEqualTo(driverId.toString());
        assertThat(msg.getValue().getSource()).isEqualTo("HTTP");
        assertThat(msg.getValue().getWindowEndsAt()).isEqualTo(ends);
    }

    @Test
    @DisplayName("ingestHttpCrash — the saved crash keeps the window end as its deadline")
    void ingestHttp_savesDeadline() {
        notADuplicate();
        when(pairingRepository.findByDeviceId("DEV-001")).thenReturn(Optional.of(testPairing));
        saveAssignsId();

        CrashAckResponse response = service.ingestHttpCrash("DEV-001", crashRequest);

        ArgumentCaptor<CrashEvent> captor = ArgumentCaptor.forClass(CrashEvent.class);
        verify(crashEventRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CrashEventStatus.RECEIVED);
        assertThat(captor.getValue().getCancellationDeadline()).isEqualTo(Instant.parse(response.getWindowEndsAt()));
    }

    @Test
    @DisplayName("ingestHttpCrash — works without a timestamp (the ESP32 has no clock)")
    void ingestHttp_noTimestamp() {
        crashRequest.setTimestamp(null);
        notADuplicate();
        when(pairingRepository.findByDeviceId("DEV-001")).thenReturn(Optional.of(testPairing));
        saveAssignsId();

        CrashAckResponse response = service.ingestHttpCrash("DEV-001", crashRequest);

        assertThat(response.getStatus()).isEqualTo("RECEIVED");
        verify(valueOperations).setIfAbsent(startsWith("crash:dedup:DEV-001:"), eq("1"), any(Duration.class));
    }

    @Test
    @DisplayName("ingestHttpCrash — duplicate payload returns DUPLICATE and does nothing else")
    void ingestHttp_duplicate() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        CrashAckResponse response = service.ingestHttpCrash("DEV-001", crashRequest);

        assertThat(response.getStatus()).isEqualTo("DUPLICATE");
        assertThat(response.getServerTime()).isNotNull();
        assertThat(response.getWindowEndsAt()).isNull();
        verify(crashEventRepository, never()).save(any());
        verify(timerService, never()).startCancellationWindow(any(), any());
        verify(kafkaProducer, never()).publishCrashEvent(any());
    }

    @Test
    @DisplayName("ingestHttpCrash — unpaired device still ingests with null driver info")
    void ingestHttp_unpairedDevice() {
        notADuplicate();
        when(pairingRepository.findByDeviceId("UNKNOWN-DEV")).thenReturn(Optional.empty());
        saveAssignsId();

        CrashAckResponse response = service.ingestHttpCrash("UNKNOWN-DEV", crashRequest);

        assertThat(response.getStatus()).isEqualTo("RECEIVED");
        ArgumentCaptor<CrashEvent> captor = ArgumentCaptor.forClass(CrashEvent.class);
        verify(crashEventRepository).save(captor.capture());
        assertThat(captor.getValue().getDriverId()).isNull();
        verify(kafkaProducer).publishCrashEvent(argThat(m -> m.getDriverId() == null));
    }

    @Test
    @DisplayName("ingestHttpCrash — a Kafka failure on CRASH_PENDING never breaks the crash intake")
    void ingestHttp_kafkaFailureIgnored() {
        notADuplicate();
        when(pairingRepository.findByDeviceId("DEV-001")).thenReturn(Optional.of(testPairing));
        saveAssignsId();
        doThrow(new RuntimeException("kafka down")).when(kafkaProducer).publishCrashEvent(any());

        CrashAckResponse response = service.ingestHttpCrash("DEV-001", crashRequest);

        assertThat(response.getStatus()).isEqualTo("RECEIVED");
        verify(timerService).startCancellationWindow(any(UUID.class), any(Runnable.class));
    }

    @Test
    @DisplayName("ingestHttpCrash — a countdown for this device is still running: DUPLICATE with the same crash id")
    void ingestHttp_runningCountdown_isDuplicate() {
        notADuplicate();
        UUID runningId = UUID.randomUUID();
        CrashEvent running = eventWithDeadline(runningId, CrashEventStatus.RECEIVED, Instant.now().plusSeconds(6));
        when(crashEventRepository.findFirstByDeviceIdOrderByCreatedAtDesc("DEV-001"))
                .thenReturn(Optional.of(running));

        CrashAckResponse response = service.ingestHttpCrash("DEV-001", crashRequest);

        assertThat(response.getStatus()).isEqualTo("DUPLICATE");
        assertThat(response.getCrashEventId()).isEqualTo(runningId.toString());
        assertThat(response.getWindowEndsAt()).isEqualTo(running.getCancellationDeadline().toString());
        assertThat(response.getServerTime()).isNotNull();
        verify(crashEventRepository, never()).save(any());
        verify(timerService, never()).startCancellationWindow(any(), any());
        verify(kafkaProducer, never()).publishCrashEvent(any());
    }

    @Test
    @DisplayName("ingestHttpCrash — same-second repeat also tells the box which crash it repeats")
    void ingestHttp_sameSecondRepeat_carriesRunningCrash() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        UUID runningId = UUID.randomUUID();
        CrashEvent running = eventWithDeadline(runningId, CrashEventStatus.RECEIVED, Instant.now().plusSeconds(8));
        when(crashEventRepository.findFirstByDeviceIdOrderByCreatedAtDesc("DEV-001"))
                .thenReturn(Optional.of(running));

        CrashAckResponse response = service.ingestHttpCrash("DEV-001", crashRequest);

        assertThat(response.getStatus()).isEqualTo("DUPLICATE");
        assertThat(response.getCrashEventId()).isEqualTo(runningId.toString());
        verify(crashEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("ingestHttpCrash — the device's last crash is already finished: a new crash starts normally")
    void ingestHttp_lastCrashFinished_startsNew() {
        notADuplicate();
        CrashEvent old = eventWithDeadline(UUID.randomUUID(), CrashEventStatus.CONFIRMED, Instant.now().minusSeconds(120));
        when(crashEventRepository.findFirstByDeviceIdOrderByCreatedAtDesc("DEV-001"))
                .thenReturn(Optional.of(old));
        when(pairingRepository.findByDeviceId("DEV-001")).thenReturn(Optional.of(testPairing));
        saveAssignsId();

        CrashAckResponse response = service.ingestHttpCrash("DEV-001", crashRequest);

        assertThat(response.getStatus()).isEqualTo("RECEIVED");
        verify(timerService).startCancellationWindow(any(UUID.class), any(Runnable.class));
    }

    @Test
    @DisplayName("ingestHttpCrash — the last crash is RECEIVED but its window is over: a new crash starts")
    void ingestHttp_lastCrashWindowOver_startsNew() {
        notADuplicate();
        CrashEvent stale = eventWithDeadline(UUID.randomUUID(), CrashEventStatus.RECEIVED, Instant.now().minusSeconds(3));
        when(crashEventRepository.findFirstByDeviceIdOrderByCreatedAtDesc("DEV-001"))
                .thenReturn(Optional.of(stale));
        when(pairingRepository.findByDeviceId("DEV-001")).thenReturn(Optional.of(testPairing));
        saveAssignsId();

        CrashAckResponse response = service.ingestHttpCrash("DEV-001", crashRequest);

        assertThat(response.getStatus()).isEqualTo("RECEIVED");
    }

    @Test
    @DisplayName("ingestHttpCrash — Redis is down: the crash is still accepted (never lose an emergency)")
    void ingestHttp_redisDown_stillIngests() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        when(pairingRepository.findByDeviceId("DEV-001")).thenReturn(Optional.of(testPairing));
        saveAssignsId();

        CrashAckResponse response = service.ingestHttpCrash("DEV-001", crashRequest);

        assertThat(response.getStatus()).isEqualTo("RECEIVED");
        verify(timerService).startCancellationWindow(any(UUID.class), any(Runnable.class));
    }

    // ══════════════════ PHONE AS THE CAR BOX (PHASE 6) ══════════════════

    private PhoneCrashRequest phoneRequest(boolean manual) {
        return PhoneCrashRequest.builder()
                .gForce(9.0).rolloverAngle(10.0).speed(60.0).gpsLat(31.52).gpsLng(74.35)
                .manual(manual).driverName("Ali Khan").driverPhone("+923001112233").build();
    }

    @Test
    @DisplayName("ingestPhoneCrash — the phone's own user is the driver, even without a pairing")
    void ingestPhone_userIsDriver() {
        notADuplicate();
        UUID user = UUID.randomUUID();
        when(pairingRepository.findByDeviceId("PHONE-" + user)).thenReturn(Optional.empty());
        saveAssignsId();

        CrashAckResponse response = service.ingestPhoneCrash(user, phoneRequest(false));

        assertThat(response.getStatus()).isEqualTo("RECEIVED");
        ArgumentCaptor<CrashEvent> captor = ArgumentCaptor.forClass(CrashEvent.class);
        verify(crashEventRepository).save(captor.capture());
        assertThat(captor.getValue().getDeviceId()).isEqualTo("PHONE-" + user);
        assertThat(captor.getValue().getDriverId()).isEqualTo(user);
        assertThat(captor.getValue().getDriverName()).isEqualTo("Ali Khan");
        assertThat(captor.getValue().getDriverPhone()).isEqualTo("+923001112233");
        verify(timerService).startCancellationWindow(any(UUID.class), any(Runnable.class));
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("ingestPhoneCrash — a held SOS button is remembered as manual SOS")
    void ingestPhone_manualSosIsMarked() {
        notADuplicate();
        UUID user = UUID.randomUUID();
        saveAssignsId();

        CrashAckResponse response = service.ingestPhoneCrash(user, phoneRequest(true));

        assertThat(response.getStatus()).isEqualTo("RECEIVED");
        verify(valueOperations).set(eq("crash:manual:" + response.getCrashEventId()), eq("1"), any(Duration.class));
    }

    @Test
    @DisplayName("ingestPhoneCrash — the driver's car box is already counting down: DUPLICATE, one accident = one crash")
    void ingestPhone_boxAlreadyCountingDown() {
        notADuplicate();
        UUID user = UUID.randomUUID();
        UUID boxCrash = UUID.randomUUID();
        when(crashEventRepository.findFirstByDriverIdOrderByCreatedAtDesc(user)).thenReturn(Optional.of(
                eventWithDeadline(boxCrash, CrashEventStatus.RECEIVED, Instant.now().plusSeconds(7))));

        CrashAckResponse response = service.ingestPhoneCrash(user, phoneRequest(false));

        assertThat(response.getStatus()).isEqualTo("DUPLICATE");
        assertThat(response.getCrashEventId()).isEqualTo(boxCrash.toString());
        verify(crashEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("onCancellationWindowExpired — manual SOS: no ML check, always CRASH_DETECTED")
    void expiry_manualSos_skipsMl() {
        UUID id = UUID.randomUUID();
        CrashEvent e = event(id, CrashEventStatus.RECEIVED);
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(e));
        when(crashEventRepository.transition(id, CrashEventStatus.RECEIVED, CrashEventStatus.PROCESSING)).thenReturn(1);
        when(crashEventRepository.transition(id, CrashEventStatus.PROCESSING, CrashEventStatus.CONFIRMED)).thenReturn(1);
        when(redisTemplate.hasKey("crash:manual:" + id)).thenReturn(true);

        service.onCancellationWindowExpired(id);

        verify(mlServiceClient, never()).predict(any());
        assertThat(e.getMlSeverity()).isEqualTo("MANUAL_SOS");
        verify(kafkaProducer).publishCrashEvent(argThat(m ->
                "CRASH_DETECTED".equals(m.getEventType()) && m.getPriorityScore() == 4));
    }

    // ══════════════════ SMS INGESTION ══════════════════

    @Test
    @DisplayName("ingestSmsCrash — bypasses the timer and goes directly to ML + Kafka")
    void ingestSms_success() {
        notADuplicate();
        when(pairingRepository.findByDeviceId("DEV-001")).thenReturn(Optional.of(testPairing));
        saveAssignsId();
        when(crashEventRepository.findById(any())).thenAnswer(inv ->
                Optional.of(event(inv.getArgument(0), CrashEventStatus.RECEIVED)));
        when(crashEventRepository.transition(any(), eq(CrashEventStatus.RECEIVED), eq(CrashEventStatus.PROCESSING)))
                .thenReturn(1);
        when(crashEventRepository.transition(any(), eq(CrashEventStatus.PROCESSING), eq(CrashEventStatus.CONFIRMED)))
                .thenReturn(1);
        when(mlServiceClient.predict(any())).thenReturn(
                MlPredictionResponse.builder().severe(true).priorityScore(4).severity("HIGH").build());

        CrashAckResponse response = service.ingestSmsCrash("DEV-001", 8.5, 45.0, 120.0, 37.77, -122.41);

        assertThat(response.getStatus()).isEqualTo("CONFIRMED");
        verify(timerService, never()).startCancellationWindow(any(), any()); // No timer for SMS
        verify(kafkaProducer).publishCrashEvent(argThat(m -> "CRASH_DETECTED".equals(m.getEventType())));
    }

    @Test
    @DisplayName("ingestSmsCrash — the saved crash gets a deadline (so the sweep can finish it if needed)")
    void ingestSms_savesDeadline() {
        notADuplicate();
        when(pairingRepository.findByDeviceId("DEV-001")).thenReturn(Optional.of(testPairing));
        saveAssignsId();
        when(crashEventRepository.findById(any())).thenReturn(Optional.empty()); // stop after saving

        service.ingestSmsCrash("DEV-001", 8.5, 45.0, 120.0, 37.77, -122.41);

        ArgumentCaptor<CrashEvent> captor = ArgumentCaptor.forClass(CrashEvent.class);
        verify(crashEventRepository).save(captor.capture());
        assertThat(captor.getValue().getSource()).isEqualTo(CrashSource.SMS);
        assertThat(captor.getValue().getStatus()).isEqualTo(CrashEventStatus.RECEIVED);
        assertThat(captor.getValue().getCancellationDeadline()).isNotNull();
    }

    @Test
    @DisplayName("ingestSmsCrash — the same SMS again (Twilio retry) is DUPLICATE and does nothing")
    void ingestSms_duplicate() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(startsWith("crash:dedup:sms:DEV-001"), anyString(), any(Duration.class)))
                .thenReturn(false);

        CrashAckResponse response = service.ingestSmsCrash("DEV-001", 8.5, 45.0, 120.0, 37.77, -122.41);

        assertThat(response.getStatus()).isEqualTo("DUPLICATE");
        verify(crashEventRepository, never()).save(any());
        verify(kafkaProducer, never()).publishCrashEvent(any());
    }

    // ══════════════════ DEVICE CANCEL (box button) ══════════════════

    @Test
    @DisplayName("cancelByDevice — cancels the pending crash and publishes CRASH_DISMISSED")
    void deviceCancel_success() {
        UUID id = UUID.randomUUID();
        CrashEvent pending = event(id, CrashEventStatus.RECEIVED);
        when(crashEventRepository.findFirstByDeviceIdOrderByCreatedAtDesc("DEV-001")).thenReturn(Optional.of(pending));
        when(crashEventRepository.transition(id, CrashEventStatus.RECEIVED, CrashEventStatus.CANCELLED)).thenReturn(1);

        CrashAckResponse response = service.cancelByDevice("DEV-001");

        assertThat(response.getStatus()).isEqualTo("CANCELLED");
        assertThat(response.getCrashEventId()).isEqualTo(id.toString());
        verify(timerService).cancelTimer(id);
        verify(kafkaProducer).publishCrashEvent(argThat(m ->
                "CRASH_DISMISSED".equals(m.getEventType()) && "CANCELLED_BY_DEVICE".equals(m.getReason())));
    }

    @Test
    @DisplayName("cancelByDevice — device with no crash gets NO_PENDING_CRASH (not an error)")
    void deviceCancel_noCrash() {
        when(crashEventRepository.findFirstByDeviceIdOrderByCreatedAtDesc("DEV-001")).thenReturn(Optional.empty());

        CrashAckResponse response = service.cancelByDevice("DEV-001");

        assertThat(response.getStatus()).isEqualTo("NO_PENDING_CRASH");
        verify(crashEventRepository, never()).transition(any(), any(), any());
    }

    @Test
    @DisplayName("cancelByDevice — window just ended and the crash was sent: TOO_LATE")
    void deviceCancel_tooLate() {
        UUID id = UUID.randomUUID();
        CrashEvent sent = eventWithDeadline(id, CrashEventStatus.CONFIRMED, Instant.now().minusSeconds(5));
        when(crashEventRepository.findFirstByDeviceIdOrderByCreatedAtDesc("DEV-001")).thenReturn(Optional.of(sent));

        CrashAckResponse response = service.cancelByDevice("DEV-001");

        assertThat(response.getStatus()).isEqualTo("TOO_LATE");
        verify(kafkaProducer, never()).publishCrashEvent(any());
    }

    @Test
    @DisplayName("cancelByDevice — an old crash from long ago is NO_PENDING_CRASH, not TOO_LATE")
    void deviceCancel_oldCrash() {
        UUID id = UUID.randomUUID();
        CrashEvent old = eventWithDeadline(id, CrashEventStatus.CONFIRMED, Instant.now().minus(Duration.ofHours(1)));
        when(crashEventRepository.findFirstByDeviceIdOrderByCreatedAtDesc("DEV-001")).thenReturn(Optional.of(old));

        assertThat(service.cancelByDevice("DEV-001").getStatus()).isEqualTo("NO_PENDING_CRASH");
    }

    @Test
    @DisplayName("cancelByDevice — an already cancelled crash is NO_PENDING_CRASH")
    void deviceCancel_alreadyCancelled() {
        CrashEvent done = event(UUID.randomUUID(), CrashEventStatus.CANCELLED);
        when(crashEventRepository.findFirstByDeviceIdOrderByCreatedAtDesc("DEV-001")).thenReturn(Optional.of(done));

        assertThat(service.cancelByDevice("DEV-001").getStatus()).isEqualTo("NO_PENDING_CRASH");
    }

    @Test
    @DisplayName("cancelByDevice — lost the race to the timer: TOO_LATE")
    void deviceCancel_lostRace() {
        UUID id = UUID.randomUUID();
        CrashEvent pending = event(id, CrashEventStatus.RECEIVED);
        CrashEvent nowProcessing = eventWithDeadline(id, CrashEventStatus.PROCESSING, Instant.now().minusSeconds(1));
        when(crashEventRepository.findFirstByDeviceIdOrderByCreatedAtDesc("DEV-001")).thenReturn(Optional.of(pending));
        when(crashEventRepository.transition(id, CrashEventStatus.RECEIVED, CrashEventStatus.CANCELLED)).thenReturn(0);
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(nowProcessing));

        CrashAckResponse response = service.cancelByDevice("DEV-001");

        assertThat(response.getStatus()).isEqualTo("TOO_LATE");
        verify(timerService, never()).cancelTimer(any());
    }

    // ══════════════════ DRIVER CANCEL (within window) ══════════════════

    @Test
    @DisplayName("cancelCrash — the owner cancels within the window")
    void cancel_success() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(event(id, CrashEventStatus.RECEIVED)));
        when(crashEventRepository.transition(id, CrashEventStatus.RECEIVED, CrashEventStatus.CANCELLED)).thenReturn(1);

        CrashAckResponse response = service.cancelCrash(id, driverId);

        assertThat(response.getStatus()).isEqualTo("CANCELLED");
        verify(timerService).cancelTimer(id);
        verify(kafkaProducer).publishCrashEvent(argThat(m ->
                "CRASH_DISMISSED".equals(m.getEventType()) && "CANCELLED_BY_USER".equals(m.getReason())));
    }

    @Test
    @DisplayName("cancelCrash — another driver is refused (403) and nothing changes")
    void cancel_wrongUser() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(event(id, CrashEventStatus.RECEIVED)));

        assertThatThrownBy(() -> service.cancelCrash(id, UUID.randomUUID()))
                .isInstanceOf(CrashAccessDeniedException.class);

        verify(crashEventRepository, never()).transition(any(), any(), any());
        verify(timerService, never()).cancelTimer(any());
    }

    @Test
    @DisplayName("cancelCrash — a crash with no known driver cannot be cancelled by anyone")
    void cancel_noOwner() {
        UUID id = UUID.randomUUID();
        CrashEvent orphan = event(id, CrashEventStatus.RECEIVED);
        orphan.setDriverId(null);
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(orphan));

        assertThatThrownBy(() -> service.cancelCrash(id, driverId))
                .isInstanceOf(CrashAccessDeniedException.class);
    }

    @Test
    @DisplayName("cancelCrash — already cancelled returns ALREADY_CANCELLED")
    void cancel_alreadyCancelled() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(event(id, CrashEventStatus.CANCELLED)));

        assertThat(service.cancelCrash(id, driverId).getStatus()).isEqualTo("ALREADY_CANCELLED");
        verify(crashEventRepository, never()).transition(any(), any(), any());
    }

    @Test
    @DisplayName("cancelCrash — a dismissed crash (ML said not severe) is ALREADY_CANCELLED")
    void cancel_alreadyDismissed() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(event(id, CrashEventStatus.DISMISSED)));

        assertThat(service.cancelCrash(id, driverId).getStatus()).isEqualTo("ALREADY_CANCELLED");
    }

    @Test
    @DisplayName("cancelCrash — after the window the answer is TOO_LATE")
    void cancel_tooLate() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(event(id, CrashEventStatus.CONFIRMED)));

        CrashAckResponse response = service.cancelCrash(id, driverId);

        assertThat(response.getStatus()).isEqualTo("TOO_LATE");
        verify(crashEventRepository, never()).transition(any(), any(), any());
    }

    @Test
    @DisplayName("cancelCrash — lost the race and the box button cancelled first: ALREADY_CANCELLED")
    void cancel_lostRaceToDevice() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findById(id)).thenReturn(
                Optional.of(event(id, CrashEventStatus.RECEIVED)),
                Optional.of(event(id, CrashEventStatus.CANCELLED)));
        when(crashEventRepository.transition(id, CrashEventStatus.RECEIVED, CrashEventStatus.CANCELLED)).thenReturn(0);

        assertThat(service.cancelCrash(id, driverId).getStatus()).isEqualTo("ALREADY_CANCELLED");
        verify(kafkaProducer, never()).publishCrashEvent(any());
    }

    @Test
    @DisplayName("cancelCrash — lost the race and the timer took it: TOO_LATE")
    void cancel_lostRaceToTimer() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findById(id)).thenReturn(
                Optional.of(event(id, CrashEventStatus.RECEIVED)),
                Optional.of(event(id, CrashEventStatus.PROCESSING)));
        when(crashEventRepository.transition(id, CrashEventStatus.RECEIVED, CrashEventStatus.CANCELLED)).thenReturn(0);

        assertThat(service.cancelCrash(id, driverId).getStatus()).isEqualTo("TOO_LATE");
    }

    @Test
    @DisplayName("cancelCrash — throws when event not found")
    void cancel_notFound() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelCrash(id, driverId))
                .isInstanceOf(CrashEventNotFoundException.class);
    }

    // ══════════════════ LATE CANCEL ══════════════════

    @Test
    @DisplayName("lateCancelCrash — the owner's late cancel publishes CRASH_CANCELLED")
    void lateCancel_success() {
        UUID id = UUID.randomUUID();
        CrashEvent confirmed = event(id, CrashEventStatus.CONFIRMED);
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(confirmed));
        when(crashEventRepository.save(any())).thenReturn(confirmed);

        CrashAckResponse response = service.lateCancelCrash(id, driverId);

        assertThat(response.getStatus()).isEqualTo("LATE_CANCELLED");
        assertThat(confirmed.getStatus()).isEqualTo(CrashEventStatus.LATE_CANCELLED);
        verify(timerService, never()).cancelTimer(any());
        verify(kafkaProducer).publishCrashEvent(argThat(m ->
                "CRASH_CANCELLED".equals(m.getEventType()) && "LATE_CANCEL".equals(m.getReason())));
    }

    @Test
    @DisplayName("lateCancelCrash — while the window is still open it also stops the timer")
    void lateCancel_windowStillOpen() {
        UUID id = UUID.randomUUID();
        CrashEvent pending = event(id, CrashEventStatus.RECEIVED);
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(pending));
        when(crashEventRepository.save(any())).thenReturn(pending);

        service.lateCancelCrash(id, driverId);

        verify(timerService).cancelTimer(id);
    }

    @Test
    @DisplayName("lateCancelCrash — another driver is refused (403)")
    void lateCancel_wrongUser() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(event(id, CrashEventStatus.CONFIRMED)));

        assertThatThrownBy(() -> service.lateCancelCrash(id, UUID.randomUUID()))
                .isInstanceOf(CrashAccessDeniedException.class);

        verify(crashEventRepository, never()).save(any());
        verify(kafkaProducer, never()).publishCrashEvent(any());
    }

    @Test
    @DisplayName("lateCancelCrash — already cancelled returns ALREADY_CANCELLED")
    void lateCancel_alreadyCancelled() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(event(id, CrashEventStatus.LATE_CANCELLED)));

        assertThat(service.lateCancelCrash(id, driverId).getStatus()).isEqualTo("ALREADY_CANCELLED");
        verify(kafkaProducer, never()).publishCrashEvent(any());
    }

    @Test
    @DisplayName("lateCancelCrash — throws when event not found")
    void lateCancel_notFound() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lateCancelCrash(id, driverId))
                .isInstanceOf(CrashEventNotFoundException.class);
    }

    // ══════════════════ WINDOW ENDED (ML + Kafka) ══════════════════

    @Test
    @DisplayName("onCancellationWindowExpired — severe: CONFIRMED and CRASH_DETECTED")
    void expiry_severe_publishes() {
        UUID id = UUID.randomUUID();
        CrashEvent e = event(id, CrashEventStatus.RECEIVED);
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(e));
        when(crashEventRepository.transition(id, CrashEventStatus.RECEIVED, CrashEventStatus.PROCESSING)).thenReturn(1);
        when(crashEventRepository.transition(id, CrashEventStatus.PROCESSING, CrashEventStatus.CONFIRMED)).thenReturn(1);
        when(mlServiceClient.predict(any())).thenReturn(
                MlPredictionResponse.builder().severe(true).priorityScore(5).severity("CRITICAL").build());

        service.onCancellationWindowExpired(id);

        assertThat(e.getStatus()).isEqualTo(CrashEventStatus.CONFIRMED);
        assertThat(e.getPriorityScore()).isEqualTo(5);
        assertThat(e.getMlSeverity()).isEqualTo("CRITICAL");
        verify(crashEventRepository).save(e);
        verify(kafkaProducer).publishCrashEvent(argThat(m ->
                "CRASH_DETECTED".equals(m.getEventType()) && m.getPriorityScore() == 5));
    }

    @Test
    @DisplayName("onCancellationWindowExpired — not severe: DISMISSED and CRASH_DISMISSED (ML_NOT_SEVERE)")
    void expiry_notSevere_dismisses() {
        UUID id = UUID.randomUUID();
        CrashEvent e = event(id, CrashEventStatus.RECEIVED);
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(e));
        when(crashEventRepository.transition(id, CrashEventStatus.RECEIVED, CrashEventStatus.PROCESSING)).thenReturn(1);
        when(crashEventRepository.transition(id, CrashEventStatus.PROCESSING, CrashEventStatus.DISMISSED)).thenReturn(1);
        when(mlServiceClient.predict(any())).thenReturn(
                MlPredictionResponse.builder().severe(false).priorityScore(1).severity("LOW").build());

        service.onCancellationWindowExpired(id);

        assertThat(e.getStatus()).isEqualTo(CrashEventStatus.DISMISSED);
        verify(kafkaProducer, never()).publishCrashEvent(argThat(m -> "CRASH_DETECTED".equals(m.getEventType())));
        verify(kafkaProducer).publishCrashEvent(argThat(m ->
                "CRASH_DISMISSED".equals(m.getEventType()) && "ML_NOT_SEVERE".equals(m.getReason())));
    }

    @Test
    @DisplayName("onCancellationWindowExpired — already cancelled or handled: does nothing")
    void expiry_alreadyHandled() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(event(id, CrashEventStatus.CANCELLED)));
        when(crashEventRepository.transition(id, CrashEventStatus.RECEIVED, CrashEventStatus.PROCESSING)).thenReturn(0);

        service.onCancellationWindowExpired(id);

        verify(mlServiceClient, never()).predict(any());
        verify(kafkaProducer, never()).publishCrashEvent(any());
        verify(crashEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("onCancellationWindowExpired — missing event: does nothing")
    void expiry_missing() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findById(id)).thenReturn(Optional.empty());

        service.onCancellationWindowExpired(id);

        verify(crashEventRepository, never()).transition(any(), any(), any());
        verify(mlServiceClient, never()).predict(any());
    }

    @Test
    @DisplayName("onCancellationWindowExpired — ML throws: fail-safe, treated as severe")
    void expiry_mlThrows_failSafe() {
        UUID id = UUID.randomUUID();
        CrashEvent e = event(id, CrashEventStatus.RECEIVED);
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(e));
        when(crashEventRepository.transition(id, CrashEventStatus.RECEIVED, CrashEventStatus.PROCESSING)).thenReturn(1);
        when(crashEventRepository.transition(id, CrashEventStatus.PROCESSING, CrashEventStatus.CONFIRMED)).thenReturn(1);
        when(mlServiceClient.predict(any())).thenThrow(new RuntimeException("ml down"));

        service.onCancellationWindowExpired(id);

        assertThat(e.getStatus()).isEqualTo(CrashEventStatus.CONFIRMED);
        assertThat(e.getMlSeverity()).isEqualTo("UNKNOWN_FALLBACK");
        verify(kafkaProducer).publishCrashEvent(argThat(m ->
                "CRASH_DETECTED".equals(m.getEventType()) && m.getPriorityScore() == 3));
    }

    @Test
    @DisplayName("onCancellationWindowExpired — ML returns nothing: fail-safe, treated as severe")
    void expiry_mlNull_failSafe() {
        UUID id = UUID.randomUUID();
        CrashEvent e = event(id, CrashEventStatus.RECEIVED);
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(e));
        when(crashEventRepository.transition(id, CrashEventStatus.RECEIVED, CrashEventStatus.PROCESSING)).thenReturn(1);
        when(crashEventRepository.transition(id, CrashEventStatus.PROCESSING, CrashEventStatus.CONFIRMED)).thenReturn(1);
        when(mlServiceClient.predict(any())).thenReturn(null);

        service.onCancellationWindowExpired(id);

        assertThat(e.getStatus()).isEqualTo(CrashEventStatus.CONFIRMED);
        verify(kafkaProducer).publishCrashEvent(argThat(m -> "CRASH_DETECTED".equals(m.getEventType())));
    }

    @Test
    @DisplayName("onCancellationWindowExpired — a late cancel arrived during the ML check: nothing is dispatched")
    void expiry_lateCancelDuringMl_notDispatched() {
        UUID id = UUID.randomUUID();
        CrashEvent e = event(id, CrashEventStatus.RECEIVED);
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(e));
        when(crashEventRepository.transition(id, CrashEventStatus.RECEIVED, CrashEventStatus.PROCESSING)).thenReturn(1);
        when(mlServiceClient.predict(any())).thenReturn(
                MlPredictionResponse.builder().severe(true).priorityScore(5).severity("CRITICAL").build());
        // transition(PROCESSING -> CONFIRMED) is not stubbed: it answers 0, the status was changed meanwhile

        service.onCancellationWindowExpired(id);

        verify(crashEventRepository, never()).save(any());
        verify(kafkaProducer, never()).publishCrashEvent(any());
    }

    @Test
    @DisplayName("onCancellationWindowExpired — Kafka is down: the crash goes back to PROCESSING for the sweep to retry")
    void expiry_kafkaDown_goesBackToProcessing() {
        UUID id = UUID.randomUUID();
        CrashEvent e = event(id, CrashEventStatus.RECEIVED);
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(e));
        when(crashEventRepository.transition(id, CrashEventStatus.RECEIVED, CrashEventStatus.PROCESSING)).thenReturn(1);
        when(crashEventRepository.transition(id, CrashEventStatus.PROCESSING, CrashEventStatus.CONFIRMED)).thenReturn(1);
        when(mlServiceClient.predict(any())).thenReturn(
                MlPredictionResponse.builder().severe(true).priorityScore(5).severity("CRITICAL").build());
        doThrow(new RuntimeException("kafka down")).when(kafkaProducer).publishCrashEvent(any());

        service.onCancellationWindowExpired(id); // must not throw

        verify(crashEventRepository).transition(id, CrashEventStatus.CONFIRMED, CrashEventStatus.PROCESSING);
    }

    // ══════════════════ OLD CRASH (service was down for a long time) ══════════════════

    @Test
    @DisplayName("expireStaleCrash — an old RECEIVED crash is closed as DISMISSED (EXPIRED), nothing is dispatched")
    void expireStale_received() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(event(id, CrashEventStatus.RECEIVED)));
        when(crashEventRepository.transition(id, CrashEventStatus.RECEIVED, CrashEventStatus.DISMISSED)).thenReturn(1);

        service.expireStaleCrash(id);

        verify(timerService).cancelTimer(id);
        verify(mlServiceClient, never()).predict(any());
        verify(kafkaProducer).publishCrashEvent(argThat(m ->
                "CRASH_DISMISSED".equals(m.getEventType()) && "EXPIRED".equals(m.getReason())));
    }

    @Test
    @DisplayName("expireStaleCrash — an old PROCESSING crash is closed the same way")
    void expireStale_processing() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(event(id, CrashEventStatus.PROCESSING)));
        when(crashEventRepository.transition(id, CrashEventStatus.RECEIVED, CrashEventStatus.DISMISSED)).thenReturn(0);
        when(crashEventRepository.transition(id, CrashEventStatus.PROCESSING, CrashEventStatus.DISMISSED)).thenReturn(1);

        service.expireStaleCrash(id);

        verify(kafkaProducer).publishCrashEvent(argThat(m -> "EXPIRED".equals(m.getReason())));
    }

    @Test
    @DisplayName("expireStaleCrash — already handled by someone else: nothing is published")
    void expireStale_alreadyHandled() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findById(id)).thenReturn(Optional.of(event(id, CrashEventStatus.CONFIRMED)));

        service.expireStaleCrash(id);

        verify(kafkaProducer, never()).publishCrashEvent(any());
    }

    @Test
    @DisplayName("expireStaleCrash — missing event: does nothing")
    void expireStale_missing() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findById(id)).thenReturn(Optional.empty());

        service.expireStaleCrash(id);

        verify(crashEventRepository, never()).transition(any(), any(), any());
    }

    // ══════════════════ IDEMPOTENCY ══════════════════

    @Test
    @DisplayName("isDuplicate — first call returns false (not duplicate)")
    void isDuplicate_firstCall() {
        notADuplicate();

        assertThat(service.isDuplicate("DEV-001", 1723300000000L)).isFalse();
    }

    @Test
    @DisplayName("isDuplicate — second call returns true (duplicate)")
    void isDuplicate_secondCall() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(service.isDuplicate("DEV-001", 1723300000000L)).isTrue();
    }

    @Test
    @DisplayName("isDuplicate — Redis problem: treated as not duplicate (the crash must go through)")
    void isDuplicate_redisDown() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertThat(service.isDuplicate("DEV-001", 1723300000000L)).isFalse();
    }
}
