package com.iers.auth.duty;

import com.iers.auth.entity.ResponderProfile;
import com.iers.auth.entity.enums.DutyStatus;
import com.iers.auth.repository.ResponderProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Runs every minute.
 *   1) Any responder who went ON_DUTY more than 1 hour ago is flipped to OFF_DUTY.
 *   2) Any responder whose heartbeat is older than 2 minutes is flipped to OFF_DUTY.
 * A flip pushes an event onto Redis channel "duty:events" so other services
 * (and eventually a Firebase push) can tell the responder.
 *
 * NEVER touches ON_MISSION responders — a mission must finish first.
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class DutyScheduler {

    static final Duration ON_DUTY_MAX = Duration.ofHours(1);
    static final Duration HEARTBEAT_STALE = Duration.ofMinutes(2);
    static final String REDIS_HB_KEY = "duty:hb:";           // + userId
    static final String REDIS_STARTED_AT_KEY = "duty:started:"; // + userId
    static final String REDIS_EVENTS_CHANNEL = "duty:events";

    private final ResponderProfileRepository repo;
    private final StringRedisTemplate redis;

    @Scheduled(fixedDelay = 60_000L) // every 60 s
    @Transactional
    public void sweep() {
        List<ResponderProfile> onDuty = repo.findAllByDutyStatusWithUser(DutyStatus.ON_DUTY);
        Instant now = Instant.now();
        for (ResponderProfile p : onDuty) {
            String userId = p.getUser().getId().toString();

            // 1. Hour cap.
            String startedAt = redis.opsForValue().get(REDIS_STARTED_AT_KEY + userId);
            if (startedAt != null) {
                try {
                    Instant started = Instant.ofEpochSecond(Long.parseLong(startedAt));
                    if (started.plus(ON_DUTY_MAX).isBefore(now)) {
                        flip(p, "HOUR_CAP");
                        continue;
                    }
                } catch (NumberFormatException ignored) { /* fall through */ }
            }

            // 2. Heartbeat timeout.
            String hb = redis.opsForValue().get(REDIS_HB_KEY + userId);
            if (hb == null) {
                // No heartbeat recorded since startup; be lenient for 2 minutes
                continue;
            }
            try {
                Instant last = Instant.ofEpochSecond(Long.parseLong(hb));
                if (last.plus(HEARTBEAT_STALE).isBefore(now)) {
                    flip(p, "HEARTBEAT_LOST");
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    private void flip(ResponderProfile p, String reason) {
        String userId = p.getUser().getId().toString();
        p.setDutyStatus(DutyStatus.OFF_DUTY);
        repo.save(p);
        redis.delete(REDIS_STARTED_AT_KEY + userId);
        redis.delete(REDIS_HB_KEY + userId);
        log.info("Auto OFF_DUTY: {} reason={}", userId, reason);
        // Fire-and-forget pub/sub so Realtime/Dispatch can notify the user later.
        redis.convertAndSend(REDIS_EVENTS_CHANNEL,
                "{\"type\":\"OFF_DUTY\",\"userId\":\"" + userId + "\",\"reason\":\"" + reason + "\"}");
    }
}
