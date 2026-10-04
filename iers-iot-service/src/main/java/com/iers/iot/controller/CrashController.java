package com.iers.iot.controller;

import com.iers.iot.dto.request.CrashPayloadRequest;
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
     * SMS failsafe crash ingestion.
     * Twilio forwards the inbound SMS as a form-encoded POST.
     * The car GSM module formats the body as: "CRASH|gForce|rollover|speed|lat|lng|deviceId"
     */
    @PostMapping("/sms")
    public ResponseEntity<CrashAckResponse> ingestSmsCrash(
            @ModelAttribute SmsCrashRequest smsRequest) {

        log.info("SMS crash received from: {}", smsRequest.getFrom());

        try {
            String[] parts = smsRequest.getBody().split("\\|");
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
     */
    @PostMapping("/{crashEventId}/cancel")
    public ResponseEntity<CrashAckResponse> cancelCrash(
            @PathVariable UUID crashEventId) {

        CrashAckResponse response = ingestionService.cancelCrash(crashEventId);
        return ResponseEntity.ok(response);
    }

    /**
     * Late cancel after the 10-second window.
     * Publishes CRASH_CANCELLED to Kafka so Dispatch can stand down.
     */
    @PostMapping("/{crashEventId}/late-cancel")
    public ResponseEntity<CrashAckResponse> lateCancelCrash(
            @PathVariable UUID crashEventId) {

        CrashAckResponse response = ingestionService.lateCancelCrash(crashEventId);
        return ResponseEntity.ok(response);
    }
}
