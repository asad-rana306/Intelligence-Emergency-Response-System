package com.iers.auth.controller;

import com.iers.auth.dto.request.PushTokenRequest;
import com.iers.auth.service.PushTokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The phone app tells us its Firebase token (after login, when Firebase rotates it)
 * and removes it again on logout.
 */
@RestController
@RequestMapping("/api/push/tokens")
@RequiredArgsConstructor
public class PushTokenController {

    private final PushTokenService pushTokenService;

    @PostMapping
    public ResponseEntity<Void> register(
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody PushTokenRequest request) {
        pushTokenService.register(UUID.fromString(userId), request.getToken(), request.getPlatform());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> unregister(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam String token) {
        pushTokenService.unregister(UUID.fromString(userId), token);
        return ResponseEntity.noContent().build();
    }
}
