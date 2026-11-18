package com.iers.auth.scheduler;

import com.iers.auth.config.DutyProperties;
import com.iers.auth.service.DutyExpiryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the duty sweep on a timer.
 *
 * With several Auth instances all of them tick, but a short Redis lock lets only one
 * of them do the work each time. The lock is only an optimization: if Redis is down the
 * sweep runs anyway, because the database update is itself safe against double runs.
 *
 * Settings: auth.duty.sweep.enabled (default true), auth.duty.sweep.interval (ISO-8601, default PT30S).
 * Keep auth.duty.sweep-lock-ttl shorter than the interval.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "auth.duty.sweep", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DutyExpiryScheduler {

    static final String LOCK_KEY = "lock:duty-sweep";

    private final DutyExpiryService expiryService;
    private final StringRedisTemplate redis;
    private final DutyProperties props;

    @Scheduled(fixedDelayString = "${auth.duty.sweep.interval:PT30S}",
               initialDelayString = "${auth.duty.sweep.initial-delay:PT20S}")
    public void sweep() {
        if (!tryLock()) {
            return; // another instance is doing it
        }
        try {
            int expired = expiryService.expireDue();
            if (expired > 0) {
                log.info("Duty sweep: {} responder(s) put OFF duty", expired);
            }
        } catch (Exception e) {
            log.error("Duty sweep failed: {}", e.getMessage(), e);
        }
    }

    private boolean tryLock() {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, "1", props.sweepLockTtl()));
        } catch (Exception e) {
            log.warn("Redis lock not available, running the duty sweep anyway: {}", e.getMessage());
            return true;
        }
    }
}
