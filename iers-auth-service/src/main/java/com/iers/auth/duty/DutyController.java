package com.iers.auth.duty;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Two endpoints added for the one-hour rule + heartbeat.
 *
 * POST /api/responders/heartbeat   — called by the Flutter app every ~30 s while on duty.
 * GET  /api/responders/duty-info   — returns: {"status":"ON_DUTY","startedAt":...,"expiresAt":...}
 *
 * The main duty toggle still goes through the existing PUT /api/responders/status.
 * The gateway already forwards /api/responders/** with JWT, so nothing in Gateway changes.
 */
@Slf4j
@RestController
@RequestMapping("/api/responders")
@RequiredArgsConstructor
public class DutyController {

    private final StringRedisTemplate redis;

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestHeader("X-User-Id") String userId) {
        redis.opsForValue().set(
                DutyScheduler.REDIS_HB_KEY + userId,
                String.valueOf(Instant.now().getEpochSecond()),
                Duration.ofMinutes(10));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/duty-info")
    public ResponseEntity<Map<String, Object>> dutyInfo(@RequestHeader("X-User-Id") String userId) {
        String started = redis.opsForValue().get(DutyScheduler.REDIS_STARTED_AT_KEY + userId);
        Instant startedAt = (started == null) ? null : safeEpoch(started);
        Instant expiresAt = (startedAt == null) ? null : startedAt.plus(DutyScheduler.ON_DUTY_MAX);
        long secondsLeft = (expiresAt == null) ? 0L
                : Math.max(0L, Duration.between(Instant.now(), expiresAt).getSeconds());
        return ResponseEntity.ok(Map.of(
                "startedAt", startedAt == null ? "" : startedAt.toString(),
                "expiresAt", expiresAt == null ? "" : expiresAt.toString(),
                "secondsLeft", secondsLeft,
                "maxOnDutyMinutes", DutyScheduler.ON_DUTY_MAX.toMinutes()));
    }

    private static Instant safeEpoch(String s) {
        try { return Instant.ofEpochSecond(Long.parseLong(s)); }
        catch (NumberFormatException e) { return null; }
    }
}
