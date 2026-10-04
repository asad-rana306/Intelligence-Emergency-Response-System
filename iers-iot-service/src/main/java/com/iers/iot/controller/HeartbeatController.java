package com.iers.iot.controller;

import com.iers.iot.dto.request.HeartbeatRequest;
import com.iers.iot.service.HeartbeatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/telemetry/heartbeat")
@RequiredArgsConstructor
public class HeartbeatController {

    private final HeartbeatService heartbeatService;

    @PostMapping
    public ResponseEntity<Void> receiveHeartbeat(
            @RequestHeader("X-Device-Id") String deviceId,
            @Valid @RequestBody HeartbeatRequest request) {
        heartbeatService.storeHeartbeat(deviceId, request);
        return ResponseEntity.ok().build();
    }
}
