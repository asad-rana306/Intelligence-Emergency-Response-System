package com.iers.iot.controller;

import com.iers.iot.dto.request.CrashPayloadRequest;
import com.iers.iot.dto.request.PhoneCrashRequest;
import com.iers.iot.dto.request.SmsCrashRequest;
import com.iers.iot.dto.response.CrashAckResponse;
import com.iers.iot.service.CrashIngestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/telemetry/crash")
@RequiredArgsConstructor
public class CrashController {

    private final CrashIngestionService ingestionService;

    /**
     * Primary crash ingestion endpoint.
     * Called by car embedded device via HTTP.
     * Authenticated by API key at the gateway (X-Device-Id injected by gateway).
     * The reply carries windowEndsAt and serverTime so phones can show the countdown.
     */
    @PostMapping
    public ResponseEntity<CrashAckResponse> ingestCrash(
            @RequestHeader("X-Device-Id") String deviceId,
            @Valid @RequestBody CrashPayloadRequest request) {

        log.info("Crash payload received: device={}, gForce={}, speed={}",
                deviceId, request.getGForce(), request.getSpeed());

        CrashAckResponse response = ingestionService.ingestHttpCrash(deviceId, request);

        if ("DUPLICATE".equals(response.getStatus())) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * PHASE 6: the driver's phone works as the car box (or the driver held SOS).
     * Phone login (JWT), not the device key. Only drivers. Same replies as the box.
     */
    @PostMapping("/phone")
    public ResponseEntity<CrashAckResponse> ingestPhoneCrash(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader(value = "X-User-Role", defaultValue = "UNKNOWN") String role,
            @Valid @RequestBody PhoneCrashRequest request) {

        if (!"DRIVER".equalsIgnoreCase(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(CrashAckResponse.builder()
                    .status("FORBIDDEN").message("Only a driver's phone can report a crash").build());
        }
        log.info("Phone crash received: user={}, manual={}, gForce={}", userId, request.isManualSos(), request.getGForce());

        CrashAckResponse response = ingestionService.ingestPhoneCrash(userId, request);
        if ("DUPLICATE".equals(response.getStatus())) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * NEW (Phase 2): the cancel button on the box.
     * Empty body. The device ID comes from the header. Always answers 200 with
     * status CANCELLED, TOO_LATE or NO_PENDING_CRASH, so the firmware stays simple.
     */
    @PostMapping("/cancel")
    public ResponseEntity<CrashAckResponse> cancelFromDevice(
            @RequestHeader("X-Device-Id") String deviceId) {

        log.info("Device cancel pressed: device={}", deviceId);
        return ResponseEntity.ok(ingestionService.cancelByDevice(deviceId));
    }

    /**
     * SMS failsafe crash ingestion.
     * Twilio forwards the inbound SMS as a form-encoded POST.
     * The car GSM module formats the body as: "CRASH|gForce|rollover|speed|lat|lng|deviceId"
     */
    @PostMapping("/sms")
    public ResponseEntity<CrashAckResponse> ingestSmsCrash(
            @ModelAttribute SmsCrashRequest smsRequest) {

        log.info("SMS crash received from: {}", smsRequest.getFrom());

        try {
            String text = smsRequest.getBody();
            String[] parts = text == null ? new String[0] : text.split("\\|");
            if (parts.length < 7 || !"CRASH".equals(parts[0])) {
                return ResponseEntity.badRequest().body(CrashAckResponse.builder()
                        .status("INVALID")
                        .message("SMS body format invalid. Expected: CRASH|gForce|rollover|speed|lat|lng|deviceId")
                        .build());
            }

            Double gForce = Double.parseDouble(parts[1]);
            Double rollover = Double.parseDouble(parts[2]);
            Double speed = Double.parseDouble(parts[3]);
            Double lat = Double.parseDouble(parts[4]);
            Double lng = Double.parseDouble(parts[5]);
            String deviceId = parts[6].trim();

            CrashAckResponse response = ingestionService.ingestSmsCrash(
                    deviceId, gForce, rollover, speed, lat, lng);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);

        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(CrashAckResponse.builder()
                    .status("PARSE_ERROR")
                    .message("Could not parse numeric values from SMS body")
                    .build());
        }
    }

    /**
     * Cancel within the 10-second window.
     * Called by the phone app when the driver taps "I AM OK".
     * Only the driver this crash belongs to may cancel it (X-User-Id comes from the gateway).
     */
    @PostMapping("/{crashEventId}/cancel")
    public ResponseEntity<CrashAckResponse> cancelCrash(
            @PathVariable UUID crashEventId,
            @RequestHeader("X-User-Id") UUID userId) {

        return ResponseEntity.ok(ingestionService.cancelCrash(crashEventId, userId));
    }

    /**
     * Late cancel after the 10-second window.
     * Publishes CRASH_CANCELLED to Kafka so Dispatch can stand down.
     * Only the driver this crash belongs to may do it.
     */
    @PostMapping("/{crashEventId}/late-cancel")
    public ResponseEntity<CrashAckResponse> lateCancelCrash(
            @PathVariable UUID crashEventId,
            @RequestHeader("X-User-Id") UUID userId) {

        return ResponseEntity.ok(ingestionService.lateCancelCrash(crashEventId, userId));
    }
}
