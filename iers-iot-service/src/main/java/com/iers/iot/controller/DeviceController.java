package com.iers.iot.controller;

import com.iers.iot.dto.request.DevicePairRequest;
import com.iers.iot.dto.response.DeviceStatusResponse;
import com.iers.iot.service.DevicePairingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DevicePairingService pairingService;

    @PostMapping("/pair")
    public ResponseEntity<DeviceStatusResponse> pairDevice(
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody DevicePairRequest request) {
        DeviceStatusResponse response =
                pairingService.pairDevice(UUID.fromString(userId), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{deviceId}/status")
    public ResponseEntity<DeviceStatusResponse> getDeviceStatus(
            @PathVariable String deviceId) {
        return ResponseEntity.ok(pairingService.getDeviceStatus(deviceId));
    }
}
