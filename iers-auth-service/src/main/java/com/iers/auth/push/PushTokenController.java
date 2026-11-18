package com.iers.auth.push;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Called by the Flutter app on login, on token refresh, and on logout.
 *
 *   POST   /api/push/tokens   { "token": "fcm...", "platform": "android" }
 *   DELETE /api/push/tokens   { "token": "fcm..." }
 *
 * Also a Dispatch-internal lookup:
 *   GET /internal/users/{userId}/push-tokens  -> ["fcm...", "fcm..."]
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PushTokenController {

    private final PushTokenRepository repo;

    @PostMapping("/api/push/tokens")
    public ResponseEntity<Void> register(@RequestHeader("X-User-Id") String userId,
                                         @Valid @RequestBody RegisterReq req) {
        UUID uid = UUID.fromString(userId);
        PushTokenEntity existing = repo.findByToken(req.getToken()).orElse(null);
        if (existing == null) {
            repo.save(PushTokenEntity.builder()
                    .userId(uid).token(req.getToken())
                    .platform(req.getPlatform() == null ? "android" : req.getPlatform())
                    .build());
        } else if (!existing.getUserId().equals(uid)) {
            // Same device, new user — move the token
            existing.setUserId(uid);
            existing.setPlatform(req.getPlatform());
            repo.save(existing);
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/push/tokens")
    public ResponseEntity<Void> unregister(@RequestHeader("X-User-Id") String userId,
                                           @RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token != null) repo.deleteByToken(token);
        return ResponseEntity.noContent().build();
    }

    /** Dispatch calls this (never reachable from outside — the Gateway blocks /internal). */
    @GetMapping("/internal/users/{userId}/push-tokens")
    public ResponseEntity<List<String>> internalLookup(@PathVariable UUID userId) {
        return ResponseEntity.ok(
                repo.findAllByUserId(userId).stream().map(PushTokenEntity::getToken).toList());
    }

    @Data public static class RegisterReq {
        @NotBlank private String token;
        private String platform; // optional
    }
}
