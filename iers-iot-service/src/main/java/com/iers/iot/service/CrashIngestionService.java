package com.iers.iot.service;

import com.iers.iot.dto.kafka.CrashEventMessage;
import com.iers.iot.dto.ml.MlPredictionRequest;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Central orchestrator for the crash ingestion pipeline.
 *
 * Flow:
 * 1. Check idempotency (Redis SETNX)
 * 2. Resolve device → driver via DevicePairing
 * 3. Persist CrashEvent with status=RECEIVED
 * 4. Start 10-second cancellation timer
 * 5. On timer expiry: call ML service → publish CRASH_DETECTED to Kafka
 * 6. On cancel: abort timer → update status=CANCELLED
 * 7. On late-cancel: publish CRASH_CANCELLED to Kafka
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrashIngestionService {

    private final CrashEventRepository crashEventRepository;
    private final DevicePairingRepository pairingRepository;
    private final StringRedisTemplate redisTemplate;
    private final CrashTimerService timerService;
    private final MlServiceClient mlServiceClient;
    private final KafkaProducerService kafkaProducer;

    private static final Duration DEDUP_TTL = Duration.ofMinutes(5);
    private static final int CANCELLATION_WINDOW_SECONDS = 10;

    // ═══════════════════════════════════════════════════════
    //  HTTP CRASH INGESTION (from car device)
    // ═══════════════════════════════════════════════════════

    @Transactional
    public CrashAckResponse ingestHttpCrash(String deviceId, CrashPayloadRequest request) {

        // ── Step 1: Idempotency check ──
        if (isDuplicate(deviceId, request.getTimestamp())) {
            log.info("Duplicate crash payload ignored: device={}, ts={}", deviceId, request.getTimestamp());
            return CrashAckResponse.builder()
                    .status("DUPLICATE")
                    .message("Crash payload already received")
                    .build();
        }

        // ── Step 2: Resolve driver from device pairing ──
        DevicePairing pairing = pairingRepository.findByDeviceId(deviceId).orElse(null);

        // ── Step 3: Persist crash event ──
        CrashEvent event = CrashEvent.builder()
                .deviceId(deviceId)
                .driverId(pairing != null ? pairing.getUserId() : null)
                .driverName(pairing != null ? pairing.getDriverName() : null)
                .driverPhone(pairing != null ? pairing.getDriverPhone() : null)
                .gForce(request.getGForce())
                .rolloverAngle(request.getRolloverAngle())
                .speed(request.getSpeed())
                .gpsLat(request.getGpsLat())
                .gpsLng(request.getGpsLng())
                .rawPayload(request.getSensorData())
                .source(CrashSource.HTTP)
                .status(CrashEventStatus.RECEIVED)
                .cancellationDeadline(Instant.now().plusSeconds(CANCELLATION_WINDOW_SECONDS))
                .build();

        event = crashEventRepository.save(event);
        final UUID crashEventId = event.getId();
        log.info("Crash event persisted: id={}, device={}, driver={}", crashEventId, deviceId,
                pairing != null ? pairing.getUserId() : "UNKNOWN");

        // ── Step 4: Start 10-second timer ──
        timerService.startCancellationWindow(crashEventId,
                () -> onCancellationWindowExpired(crashEventId));

        return CrashAckResponse.builder()
                .crashEventId(crashEventId.toString())
                .status("RECEIVED")
                .message("Crash registered. 10-second cancellation window started.")
                .build();
    }

    // ═══════════════════════════════════════════════════════
    //  SMS CRASH INGESTION (Twilio webhook — offline failsafe)
    // ═══════════════════════════════════════════════════════

    @Transactional
    public CrashAckResponse ingestSmsCrash(String deviceId, Double gForce, Double rolloverAngle,
                                           Double speed, Double lat, Double lng) {
        DevicePairing pairing = pairingRepository.findByDeviceId(deviceId).orElse(null);

        CrashEvent event = CrashEvent.builder()
                .deviceId(deviceId)
                .driverId(pairing != null ? pairing.getUserId() : null)
                .driverName(pairing != null ? pairing.getDriverName() : null)
                .driverPhone(pairing != null ? pairing.getDriverPhone() : null)
                .gForce(gForce).rolloverAngle(rolloverAngle).speed(speed)
                .gpsLat(lat).gpsLng(lng)
                .source(CrashSource.SMS)
                .status(CrashEventStatus.RECEIVED)
                .build();

        event = crashEventRepository.save(event);
        log.info("SMS crash ingested: id={}, device={}", event.getId(), deviceId);

        // SMS bypasses the 10-second window — go directly to ML + Kafka
        onCancellationWindowExpired(event.getId());

        return CrashAckResponse.builder()
                .crashEventId(event.getId().toString())
                .status("CONFIRMED")
                .message("SMS crash processed immediately (no cancellation window).")
                .build();
    }

    // ═══════════════════════════════════════════════════════
    //  CANCEL (within 10-second window)
    // ═══════════════════════════════════════════════════════

    @Transactional
    public CrashAckResponse cancelCrash(UUID crashEventId) {
        CrashEvent event = crashEventRepository.findById(crashEventId)
                .orElseThrow(() -> new CrashEventNotFoundException(
                        "Crash event not found: " + crashEventId));

        if (event.getStatus() == CrashEventStatus.CANCELLED
                || event.getStatus() == CrashEventStatus.LATE_CANCELLED) {
            return CrashAckResponse.builder()
                    .crashEventId(crashEventId.toString())
                    .status("ALREADY_CANCELLED")
                    .message("This crash event was already cancelled.")
                    .build();
        }

        // Attempt to cancel the timer
        boolean timerCancelled = timerService.cancelTimer(crashEventId);
        if (!timerCancelled && event.getStatus() == CrashEventStatus.CONFIRMED) {
            return CrashAckResponse.builder()
                    .crashEventId(crashEventId.toString())
                    .status("TOO_LATE")
                    .message("Cancellation window expired. Use late-cancel instead.")
                    .build();
        }

        event.setStatus(CrashEventStatus.CANCELLED);
        crashEventRepository.save(event);
        log.info("Crash CANCELLED within window: id={}", crashEventId);

        return CrashAckResponse.builder()
                .crashEventId(crashEventId.toString())
                .status("CANCELLED")
                .message("Crash alert cancelled. No dispatch will occur.")
                .build();
    }

    // ═══════════════════════════════════════════════════════
    //  LATE CANCEL (after 10-second window)
    // ═══════════════════════════════════════════════════════

    @Transactional
    public CrashAckResponse lateCancelCrash(UUID crashEventId) {
        CrashEvent event = crashEventRepository.findById(crashEventId)
                .orElseThrow(() -> new CrashEventNotFoundException(
                        "Crash event not found: " + crashEventId));

        if (event.getStatus() == CrashEventStatus.CANCELLED
                || event.getStatus() == CrashEventStatus.LATE_CANCELLED) {
            return CrashAckResponse.builder()
                    .crashEventId(crashEventId.toString())
                    .status("ALREADY_CANCELLED")
                    .message("This crash event was already cancelled.")
                    .build();
        }

        event.setStatus(CrashEventStatus.LATE_CANCELLED);
        crashEventRepository.save(event);

        // Publish CRASH_CANCELLED to Kafka so Dispatch can stand down
        CrashEventMessage message = CrashEventMessage.builder()
                .eventType("CRASH_CANCELLED")
                .crashEventId(crashEventId.toString())
                .driverId(event.getDriverId() != null ? event.getDriverId().toString() : null)
                .deviceId(event.getDeviceId())
                .driverName(event.getDriverName())
                .driverPhone(event.getDriverPhone())
                .reason("LATE_CANCEL")
                .timestamp(Instant.now())
                .build();

        kafkaProducer.publishCrashEvent(message);
        log.info("Crash LATE_CANCELLED — CRASH_CANCELLED published to Kafka: id={}", crashEventId);

        return CrashAckResponse.builder()
                .crashEventId(crashEventId.toString())
                .status("LATE_CANCELLED")
                .message("Late cancellation processed. Dispatch will be notified to stand down.")
                .build();
    }

    // ═══════════════════════════════════════════════════════
    //  TIMER EXPIRY CALLBACK (runs on the timer thread)
    // ═══════════════════════════════════════════════════════

    void onCancellationWindowExpired(UUID crashEventId) {
        CrashEvent event = crashEventRepository.findById(crashEventId).orElse(null);
        if (event == null || event.getStatus() == CrashEventStatus.CANCELLED) {
            log.info("Timer expired but event {} is already cancelled or missing", crashEventId);
            return;
        }

        // ── ML Triage ──
        event.setStatus(CrashEventStatus.PROCESSING);
        crashEventRepository.save(event);

        MlPredictionRequest mlRequest = MlPredictionRequest.builder()
                .gForce(event.getGForce())
                .rolloverAngle(event.getRolloverAngle())
                .speed(event.getSpeed())
                .sensorData(event.getRawPayload())
                .build();

        MlPredictionResponse mlResponse = mlServiceClient.predict(mlRequest);

        event.setMlSeverity(mlResponse.getSeverity());
        event.setPriorityScore(mlResponse.getPriorityScore());
        event.setStatus(CrashEventStatus.CONFIRMED);
        crashEventRepository.save(event);

        log.info("ML triage complete for {}: severe={}, priority={}",
                crashEventId, mlResponse.isSevere(), mlResponse.getPriorityScore());

        // ── Publish to Kafka ──
        if (mlResponse.isSevere()) {
            CrashEventMessage message = CrashEventMessage.builder()
                    .eventType("CRASH_DETECTED")
                    .crashEventId(crashEventId.toString())
                    .driverId(event.getDriverId() != null ? event.getDriverId().toString() : null)
                    .deviceId(event.getDeviceId())
                    .driverName(event.getDriverName())
                    .driverPhone(event.getDriverPhone())
                    .gpsLat(event.getGpsLat())
                    .gpsLng(event.getGpsLng())
                    .speed(event.getSpeed())
                    .gForce(event.getGForce())
                    .priorityScore(mlResponse.getPriorityScore())
                    .source(event.getSource().name())
                    .timestamp(Instant.now())
                    .build();

            kafkaProducer.publishCrashEvent(message);
        } else {
            log.info("ML determined crash {} is NOT severe — no dispatch", crashEventId);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  IDEMPOTENCY (Redis SETNX)
    // ═══════════════════════════════════════════════════════

    boolean isDuplicate(String deviceId, Long timestamp) {
        // Floor timestamp to nearest second to catch retries within the same second
        long bucketedTs = timestamp / 1000;
        String dedupKey = "crash:dedup:" + deviceId + ":" + bucketedTs;

        Boolean wasSet = redisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "1", DEDUP_TTL);

        // If setIfAbsent returns false, the key already existed → duplicate
        return !Boolean.TRUE.equals(wasSet);
    }
}
