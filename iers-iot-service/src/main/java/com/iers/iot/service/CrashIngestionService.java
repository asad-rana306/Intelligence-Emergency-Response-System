package com.iers.iot.service;

import com.iers.iot.dto.kafka.CrashEventMessage;
import com.iers.iot.dto.ml.MlPredictionRequest;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Central orchestrator for the crash pipeline.
 *
 * Flow:
 *  1. HTTP crash arrives -> dedup -> save as RECEIVED -> start the 10-second timer
 *     -> publish CRASH_PENDING (phones show the countdown)
 *  2. Window ends (timer, or the sweep if the timer was lost) -> RECEIVED -> PROCESSING -> ML check
 *     -> severe:     CONFIRMED + CRASH_DETECTED
 *     -> not severe: DISMISSED + CRASH_DISMISSED (ML_NOT_SEVERE)
 *  3. Cancel inside the window (box button or driver phone) -> CANCELLED + CRASH_DISMISSED
 *  4. Late cancel (window over) -> LATE_CANCELLED + CRASH_CANCELLED
 *
 * Every move out of RECEIVED goes through repository.transition(), which only succeeds for one
 * caller. So the timer, the sweep and a cancel can never handle the same crash twice.
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
    private static final Duration SMS_DEDUP_TTL = Duration.ofMinutes(1);
    private static final String MANUAL_SOS_KEY = "crash:manual:";
    private static final int CANCELLATION_WINDOW_SECONDS = 10;
    /** After the window, a box cancel still answers TOO_LATE (not NO_PENDING_CRASH) for this long. */
    private static final Duration TOO_LATE_MEMORY = Duration.ofMinutes(2);

    // ═══════════════════════════════════════════════════════
    //  HTTP CRASH INGESTION (from car device)
    // ═══════════════════════════════════════════════════════

    @Transactional
    public CrashAckResponse ingestHttpCrash(String deviceId, CrashPayloadRequest request) {
        return ingestCrash(deviceId, request, null, null, null);
    }

    // ═══════════════════════════════════════════════════════
    //  PHONE AS THE CAR BOX (PHASE 6)
    // ═══════════════════════════════════════════════════════

    /** The phone's own device id. One per driver, so it never collides with a real box id. */
    public static String phoneDeviceId(UUID userId) {
        return "PHONE-" + userId;
    }

    /**
     * The driver's phone detected a crash (or the driver held the SOS button).
     * Same flow as the box: 10-second window, then ML, then dispatch.
     * A manual SOS skips the ML check: a person asked for help, so help is always sent.
     */
    @Transactional
    public CrashAckResponse ingestPhoneCrash(UUID userId, PhoneCrashRequest request) {
        CrashAckResponse reply = ingestCrash(phoneDeviceId(userId), request.toCrashPayload(),
                userId, request.getDriverName(), request.getDriverPhone());
        if (request.isManualSos() && "RECEIVED".equals(reply.getStatus()) && reply.getCrashEventId() != null) {
            markManualSos(UUID.fromString(reply.getCrashEventId()));
        }
        return reply;
    }

    private CrashAckResponse ingestCrash(String deviceId, CrashPayloadRequest request,
                                         UUID fallbackDriverId, String fallbackName, String fallbackPhone) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // The ESP32 has no clock, so the timestamp is optional. Missing -> use the server time.
        long stamp = request.getTimestamp() != null ? request.getTimestamp() : now.toEpochMilli();

        // ── Step 1: Idempotency check ──
        // (a) the same device sent again inside the same second (a plain retry)
        if (isDuplicate(deviceId, stamp)) {
            log.info("Duplicate crash payload ignored: device={}, ts={}", deviceId, stamp);
            return duplicateReply(now, findRunningCrash(deviceId, now));
        }
        // (b) a countdown for this device is still running. A firmware retry a few seconds later
        //     (or a second impact) is the same emergency and must not start a second crash.
        CrashEvent running = findRunningCrash(deviceId, now);
        if (running != null) {
            log.info("Crash ignored: device {} already has crash {} counting down", deviceId, running.getId());
            return duplicateReply(now, running);
        }

        // ── Step 2: Resolve driver from device pairing ──
        DevicePairing pairing = pairingRepository.findByDeviceId(deviceId).orElse(null);
        UUID driverId = pairing != null ? pairing.getUserId() : fallbackDriverId;
        String driverName = pairing != null ? pairing.getDriverName() : fallbackName;
        String driverPhone = pairing != null ? pairing.getDriverPhone() : fallbackPhone;

        // (c) PHASE 6: the same driver's OTHER device (box and phone) is already counting down.
        //     One accident, one crash.
        if (driverId != null) {
            CrashEvent sameDriver = crashEventRepository.findFirstByDriverIdOrderByCreatedAtDesc(driverId).orElse(null);
            if (isCountingDown(sameDriver, now)) {
                log.info("Crash ignored: driver {} already has crash {} counting down", driverId, sameDriver.getId());
                return duplicateReply(now, sameDriver);
            }
        }

        // ── Step 3: Persist crash event ──
        Instant windowEndsAt = now.plusSeconds(CANCELLATION_WINDOW_SECONDS);
        CrashEvent event = CrashEvent.builder()
                .deviceId(deviceId)
                .driverId(driverId)
                .driverName(driverName)
                .driverPhone(driverPhone)
                .gForce(request.getGForce())
                .rolloverAngle(request.getRolloverAngle())
                .speed(request.getSpeed())
                .gpsLat(request.getGpsLat())
                .gpsLng(request.getGpsLng())
                .rawPayload(request.getSensorData())
                .source(CrashSource.HTTP)
                .status(CrashEventStatus.RECEIVED)
                .cancellationDeadline(windowEndsAt)
                .build();

        event = crashEventRepository.save(event);
        final UUID crashEventId = event.getId();
        log.info("Crash event persisted: id={}, device={}, driver={}", crashEventId, deviceId,
                driverId != null ? driverId : "UNKNOWN");

        // ── Step 4: Start 10-second timer ──
        timerService.startCancellationWindow(crashEventId,
                () -> onCancellationWindowExpired(crashEventId));

        // ── Step 5: Tell the phones a countdown is running ──
        publishSafely(CrashEventMessage.builder()
                .eventType("CRASH_PENDING")
                .crashEventId(crashEventId.toString())
                .driverId(idOf(event))
                .deviceId(deviceId)
                .source(CrashSource.HTTP.name())
                .windowEndsAt(windowEndsAt)
                .timestamp(now)
                .build());

        return CrashAckResponse.builder()
                .crashEventId(crashEventId.toString())
                .status("RECEIVED")
                .message("Crash registered. 10-second cancellation window started.")
                .windowEndsAt(windowEndsAt.toString())
                .serverTime(now.toString())
                .build();
    }

    // ═══════════════════════════════════════════════════════
    //  SMS CRASH INGESTION (Twilio webhook — offline failsafe)
    // ═══════════════════════════════════════════════════════

    @Transactional
    public CrashAckResponse ingestSmsCrash(String deviceId, Double gForce, Double rolloverAngle,
                                           Double speed, Double lat, Double lng) {
        // Twilio sends the same message again when it does not get an answer in time.
        if (isSmsDuplicate(deviceId, gForce, rolloverAngle, speed, lat, lng)) {
            log.info("Duplicate SMS crash ignored: device={}", deviceId);
            return CrashAckResponse.builder()
                    .status("DUPLICATE")
                    .message("This SMS crash was already received")
                    .build();
        }

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
                // No countdown for SMS. The deadline is "now", so if the immediate processing below
                // fails, the sweep finishes this crash a few seconds later.
                .cancellationDeadline(Instant.now())
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
    //  CANCEL FROM THE BOX (device cancel button) — NEW
    // ═══════════════════════════════════════════════════════

    /**
     * The button on the box. The device ID comes from the gateway header, so the firmware only
     * sends the API key. Always answers normally (never an error) to keep the firmware simple.
     */
    @Transactional
    public CrashAckResponse cancelByDevice(String deviceId) {
        CrashEvent latest = crashEventRepository.findFirstByDeviceIdOrderByCreatedAtDesc(deviceId).orElse(null);
        if (latest == null) {
            return noPendingCrash();
        }
        UUID id = latest.getId();

        if (latest.getStatus() == CrashEventStatus.RECEIVED) {
            if (crashEventRepository.transition(id, CrashEventStatus.RECEIVED, CrashEventStatus.CANCELLED) == 1) {
                timerService.cancelTimer(id);
                publishDismissed(latest, "CANCELLED_BY_DEVICE");
                log.info("Crash CANCELLED by device button: id={}, device={}", id, deviceId);
                return ack(id, "CANCELLED", "Crash alert cancelled. No dispatch will occur.");
            }
            // Someone else (timer, sweep or the phone) got there first — look again.
            latest = crashEventRepository.findById(id).orElse(latest);
        }
        return replyForClosedWindow(latest);
    }

    private CrashAckResponse replyForClosedWindow(CrashEvent event) {
        boolean recent = event.getCancellationDeadline() != null
                && Instant.now().isBefore(event.getCancellationDeadline().plus(TOO_LATE_MEMORY));
        boolean wasSent = event.getStatus() == CrashEventStatus.PROCESSING
                || event.getStatus() == CrashEventStatus.CONFIRMED;
        if (recent && wasSent) {
            return ack(event.getId(), "TOO_LATE", "The cancel window is over. Use the phone app to stand down.");
        }
        return noPendingCrash();
    }

    private CrashAckResponse noPendingCrash() {
        return CrashAckResponse.builder()
                .status("NO_PENDING_CRASH")
                .message("No crash is waiting to be cancelled.")
                .build();
    }

    // ═══════════════════════════════════════════════════════
    //  CANCEL FROM THE PHONE (within 10-second window)
    // ═══════════════════════════════════════════════════════

    @Transactional
    public CrashAckResponse cancelCrash(UUID crashEventId, UUID userId) {
        CrashEvent event = load(crashEventId);
        requireOwner(event, userId);

        if (isClosedWithoutDispatch(event.getStatus())) {
            return alreadyCancelled(crashEventId);
        }
        if (event.getStatus() != CrashEventStatus.RECEIVED) {
            return tooLate(crashEventId);
        }

        if (crashEventRepository.transition(crashEventId, CrashEventStatus.RECEIVED, CrashEventStatus.CANCELLED) == 1) {
            timerService.cancelTimer(crashEventId);
            publishDismissed(event, "CANCELLED_BY_USER");
            log.info("Crash CANCELLED within window: id={}", crashEventId);
            return ack(crashEventId, "CANCELLED", "Crash alert cancelled. No dispatch will occur.");
        }

        // Lost the race to the timer, the sweep or the box button — look again.
        CrashEventStatus now = crashEventRepository.findById(crashEventId)
                .map(CrashEvent::getStatus).orElse(event.getStatus());
        return isClosedWithoutDispatch(now) ? alreadyCancelled(crashEventId) : tooLate(crashEventId);
    }

    // ═══════════════════════════════════════════════════════
    //  LATE CANCEL (after 10-second window)
    // ═══════════════════════════════════════════════════════

    @Transactional
    public CrashAckResponse lateCancelCrash(UUID crashEventId, UUID userId) {
        CrashEvent event = load(crashEventId);
        requireOwner(event, userId);

        if (isClosedWithoutDispatch(event.getStatus())) {
            return alreadyCancelled(crashEventId);
        }

        boolean windowStillOpen = event.getStatus() == CrashEventStatus.RECEIVED;
        event.setStatus(CrashEventStatus.LATE_CANCELLED);
        crashEventRepository.save(event);
        if (windowStillOpen) {
            timerService.cancelTimer(crashEventId);
        }

        // Publish CRASH_CANCELLED to Kafka so Dispatch can stand down
        CrashEventMessage message = CrashEventMessage.builder()
                .eventType("CRASH_CANCELLED")
                .crashEventId(crashEventId.toString())
                .driverId(idOf(event))
                .deviceId(event.getDeviceId())
                .driverName(event.getDriverName())
                .driverPhone(event.getDriverPhone())
                .reason("LATE_CANCEL")
                .timestamp(Instant.now())
                .build();

        kafkaProducer.publishCrashEvent(message);
        log.info("Crash LATE_CANCELLED — CRASH_CANCELLED published to Kafka: id={}", crashEventId);

        return ack(crashEventId, "LATE_CANCELLED",
                "Late cancellation processed. Dispatch will be notified to stand down.");
    }

    // ═══════════════════════════════════════════════════════
    //  WINDOW ENDED (timer, SMS shortcut, or the sweep)
    // ═══════════════════════════════════════════════════════

    public void onCancellationWindowExpired(UUID crashEventId) {
        CrashEvent event = crashEventRepository.findById(crashEventId).orElse(null);
        if (event == null) {
            log.info("Window ended but crash event {} is missing", crashEventId);
            return;
        }

        // Only one caller wins this move. A cancel, the timer and the sweep can all arrive together.
        if (crashEventRepository.transition(crashEventId,
                CrashEventStatus.RECEIVED, CrashEventStatus.PROCESSING) == 0) {
            log.info("Window ended but crash {} is no longer RECEIVED (cancelled or already handled)", crashEventId);
            return;
        }
        event.setStatus(CrashEventStatus.PROCESSING);

        // ── ML Triage ──
        MlPredictionRequest mlRequest = MlPredictionRequest.builder()
                .gForce(event.getGForce())
                .rolloverAngle(event.getRolloverAngle())
                .speed(event.getSpeed())
                .sensorData(event.getRawPayload())
                .build();

        MlPredictionResponse mlResponse;
        try {
            mlResponse = isManualSos(crashEventId)
                    // A person held the SOS button: always send help, no ML check.
                    ? MlPredictionResponse.builder().severe(true).priorityScore(4).severity("MANUAL_SOS").build()
                    : mlServiceClient.predict(mlRequest);
        } catch (Exception ex) {
            log.warn("ML call failed for {} — assuming severe (fail-safe): {}", crashEventId, ex.getMessage());
            mlResponse = null;
        }
        if (mlResponse == null) {
            // Same fail-safe as the circuit breaker: when unsure, dispatch.
            mlResponse = MlPredictionResponse.builder()
                    .severe(true).priorityScore(3).severity("UNKNOWN_FALLBACK").build();
        }

        CrashEventStatus finalStatus = mlResponse.isSevere() ? CrashEventStatus.CONFIRMED : CrashEventStatus.DISMISSED;

        // The driver may have sent a late cancel while the ML check was running. Only finish the
        // crash if it is still PROCESSING, so a cancelled crash can never be dispatched afterwards.
        if (crashEventRepository.transition(crashEventId, CrashEventStatus.PROCESSING, finalStatus) == 0) {
            log.info("Crash {} changed while the ML check ran (late cancel?) — not dispatching", crashEventId);
            return;
        }

        event.setMlSeverity(mlResponse.getSeverity());
        event.setPriorityScore(mlResponse.getPriorityScore());
        event.setStatus(finalStatus);
        crashEventRepository.save(event);

        log.info("ML triage complete for {}: severe={}, priority={}",
                crashEventId, mlResponse.isSevere(), mlResponse.getPriorityScore());

        // ── Publish to Kafka ──
        if (mlResponse.isSevere()) {
            CrashEventMessage message = CrashEventMessage.builder()
                    .eventType("CRASH_DETECTED")
                    .crashEventId(crashEventId.toString())
                    .driverId(idOf(event))
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

            try {
                kafkaProducer.publishCrashEvent(message);
            } catch (RuntimeException ex) {
                // Kafka is down. Do NOT lose the emergency: put the crash back to PROCESSING.
                // The sweep picks it up again after a minute and tries once more.
                log.error("Could not publish CRASH_DETECTED for {} — the sweep will retry: {}",
                        crashEventId, ex.getMessage());
                crashEventRepository.transition(crashEventId, CrashEventStatus.CONFIRMED, CrashEventStatus.PROCESSING);
            }
        } else {
            log.info("ML determined crash {} is NOT severe — no dispatch", crashEventId);
            publishDismissed(event, "ML_NOT_SEVERE");
        }
    }

    /**
     * A crash far too old to dispatch for (the service was down for a long time). Close it
     * without ML or dispatch and tell the others with CRASH_DISMISSED (reason EXPIRED).
     */
    public void expireStaleCrash(UUID crashEventId) {
        CrashEvent event = crashEventRepository.findById(crashEventId).orElse(null);
        if (event == null) {
            return;
        }
        boolean closed =
                crashEventRepository.transition(crashEventId, CrashEventStatus.RECEIVED, CrashEventStatus.DISMISSED) == 1
                        || crashEventRepository.transition(crashEventId, CrashEventStatus.PROCESSING, CrashEventStatus.DISMISSED) == 1;
        if (closed) {
            timerService.cancelTimer(crashEventId);
            publishDismissed(event, "EXPIRED");
            log.warn("Crash {} closed as EXPIRED (too old to dispatch)", crashEventId);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  IDEMPOTENCY (Redis SETNX)
    // ═══════════════════════════════════════════════════════

    boolean isDuplicate(String deviceId, Long timestamp) {
        // Floor timestamp to nearest second to catch retries within the same second
        long bucketedTs = timestamp / 1000;
        String dedupKey = "crash:dedup:" + deviceId + ":" + bucketedTs;
        return seenBefore(dedupKey, DEDUP_TTL);
    }

    /** Same SMS text from the same device within a minute = Twilio sending it again. */
    boolean isSmsDuplicate(String deviceId, Double gForce, Double rolloverAngle,
                           Double speed, Double lat, Double lng) {
        String fingerprint = deviceId + "|" + gForce + "|" + rolloverAngle + "|" + speed + "|" + lat + "|" + lng;
        return seenBefore("crash:dedup:sms:" + fingerprint, SMS_DEDUP_TTL);
    }

    /**
     * Redis SETNX. Returns true when the key already existed.
     * If Redis itself is down we answer "not seen": a duplicate crash is far less harmful than
     * a crash that is lost because the duplicate check crashed.
     */
    private boolean seenBefore(String key, Duration ttl) {
        try {
            Boolean wasSet = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
            // If setIfAbsent returns false, the key already existed → duplicate
            return !Boolean.TRUE.equals(wasSet);
        } catch (RuntimeException ex) {
            log.warn("Duplicate check skipped, Redis problem: {}", ex.getMessage());
            return false;
        }
    }

    /** The device's newest crash, if its countdown is still running right now. */
    private CrashEvent findRunningCrash(String deviceId, Instant now) {
        CrashEvent latest = crashEventRepository.findFirstByDeviceIdOrderByCreatedAtDesc(deviceId).orElse(null);
        return isCountingDown(latest, now) ? latest : null;
    }

    private static boolean isCountingDown(CrashEvent event, Instant now) {
        return event != null
                && event.getStatus() == CrashEventStatus.RECEIVED
                && event.getCancellationDeadline() != null
                && event.getCancellationDeadline().isAfter(now);
    }

    /** Remember that this crash is a manual SOS (Redis, 1 hour). If Redis is down it goes through ML instead. */
    private void markManualSos(UUID crashEventId) {
        try {
            redisTemplate.opsForValue().set(MANUAL_SOS_KEY + crashEventId, "1", Duration.ofHours(1));
        } catch (RuntimeException ex) {
            log.warn("Could not mark crash {} as manual SOS: {}", crashEventId, ex.getMessage());
        }
    }

    boolean isManualSos(UUID crashEventId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(MANUAL_SOS_KEY + crashEventId));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** 200 DUPLICATE. If we know the crash it repeats, the reply carries its id and window end. */
    private CrashAckResponse duplicateReply(Instant now, CrashEvent original) {
        String id = original != null ? original.getId().toString() : null;
        String windowEndsAt = original != null ? original.getCancellationDeadline().toString() : null;
        return CrashAckResponse.builder()
                .crashEventId(id)
                .status("DUPLICATE")
                .message("Crash payload already received")
                .windowEndsAt(windowEndsAt)
                .serverTime(now.toString())
                .build();
    }

    // ═══════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════

    private CrashEvent load(UUID crashEventId) {
        return crashEventRepository.findById(crashEventId)
                .orElseThrow(() -> new CrashEventNotFoundException("Crash event not found: " + crashEventId));
    }

    /** Only the driver this crash belongs to may cancel it. */
    private void requireOwner(CrashEvent event, UUID userId) {
        if (event.getDriverId() == null || !event.getDriverId().equals(userId)) {
            throw new CrashAccessDeniedException("This crash does not belong to you");
        }
    }

    private boolean isClosedWithoutDispatch(CrashEventStatus status) {
        return status == CrashEventStatus.CANCELLED
                || status == CrashEventStatus.LATE_CANCELLED
                || status == CrashEventStatus.DISMISSED;
    }

    private CrashAckResponse alreadyCancelled(UUID id) {
        return ack(id, "ALREADY_CANCELLED", "This crash event was already cancelled.");
    }

    private CrashAckResponse tooLate(UUID id) {
        return ack(id, "TOO_LATE", "Cancellation window expired. Use late-cancel instead.");
    }

    private CrashAckResponse ack(UUID id, String status, String message) {
        return CrashAckResponse.builder()
                .crashEventId(id.toString())
                .status(status)
                .message(message)
                .build();
    }

    private String idOf(CrashEvent event) {
        return event.getDriverId() != null ? event.getDriverId().toString() : null;
    }

    /** CRASH_DISMISSED: the window ended and nothing will be dispatched. */
    private void publishDismissed(CrashEvent event, String reason) {
        publishSafely(CrashEventMessage.builder()
                .eventType("CRASH_DISMISSED")
                .crashEventId(event.getId().toString())
                .driverId(idOf(event))
                .deviceId(event.getDeviceId())
                .source(event.getSource() != null ? event.getSource().name() : null)
                .reason(reason)
                .timestamp(Instant.now())
                .build());
    }

    /** For the events that only inform phones: a Kafka problem must never break the crash flow. */
    private void publishSafely(CrashEventMessage message) {
        try {
            kafkaProducer.publishCrashEvent(message);
        } catch (RuntimeException ex) {
            log.error("Could not publish {} for crash {}: {}",
                    message.getEventType(), message.getCrashEventId(), ex.getMessage());
        }
    }
}
