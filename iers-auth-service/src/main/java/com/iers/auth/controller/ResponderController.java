package com.iers.auth.controller;

import com.iers.auth.dto.request.UpdateDutyStatusRequest;
import com.iers.auth.dto.request.UpdateLocationRequest;
import com.iers.auth.dto.response.ResponderProfileResponse;
import com.iers.auth.service.ResponderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/responders")
@RequiredArgsConstructor
public class ResponderController {

    private final ResponderService responderService;

    @PutMapping("/status")
    public ResponseEntity<ResponderProfileResponse> updateStatus(
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody UpdateDutyStatusRequest request) {
        return ResponseEntity.ok(
                responderService.updateDutyStatus(UUID.fromString(userId), request));
    }

    @PutMapping("/location")
    public ResponseEntity<ResponderProfileResponse> updateLocation(
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody UpdateLocationRequest request) {
        return ResponseEntity.ok(
                responderService.updateLocation(UUID.fromString(userId), request));
    }
}
