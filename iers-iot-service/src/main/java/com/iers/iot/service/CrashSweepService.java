package com.iers.iot.service;

import com.iers.iot.entity.CrashEvent;
import com.iers.iot.entity.enums.CrashEventStatus;
import com.iers.iot.repository.CrashEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Restart safety for the crash pipeline. Every 5 seconds it looks for crashes that got stuck:
 *
 *  1. Still RECEIVED, deadline passed: the in-memory countdown timer was lost (restart).
 *     The sweep finishes them (ML check, then CRASH_DETECTED or CRASH_DISMISSED).
 *     The 2-second grace stops it from racing the normal timer. If both ever fire together,
 *     repository.transition() lets only one of them win.
 *  2. Still PROCESSING for over a minute: the service stopped in the middle of the ML check or
 *     Kafka was down when the crash had to be sent. The sweep puts them back and tries again.
 *  3. Older than 30 minutes: far too old to send an ambulance for. These are closed as DISMISSED
 *     (CRASH_DISMISSED, reason EXPIRED) so a restart after a long outage never dispatches old crashes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrashSweepService {

    static final long GRACE_SECONDS = 2;
    static final long STUCK_PROCESSING_SECONDS = 60;
    static final Duration MAX_AGE = Duration.ofMinutes(30);

    private final CrashEventRepository crashEventRepository;
    private final CrashTimerService timerService;
    private final CrashIngestionService ingestionService;

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void sweep() {
        Instant now = Instant.now();
        finishLostTimers(now);
        retryStuckProcessing(now);
    }

    private void finishLostTimers(Instant now) {
        List<CrashEvent> overdue = crashEventRepository.findByStatusAndCancellationDeadlineBefore(
                CrashEventStatus.RECEIVED, now.minusSeconds(GRACE_SECONDS));

        for (CrashEvent event : overdue) {
            if (isTooOld(event, now)) {
                expire(event);
                continue;
            }
            if (timerService.isTimerPending(event.getId())) {
                continue; // the normal timer is still alive and will handle it
            }
            log.warn("Sweep: finishing crash {} whose timer was lost (deadline {})",
                    event.getId(), event.getCancellationDeadline());
            finish(event);
        }
    }

    private void retryStuckProcessing(Instant now) {
        List<CrashEvent> stuck = crashEventRepository.findByStatusAndCancellationDeadlineBefore(
                CrashEventStatus.PROCESSING, now.minusSeconds(STUCK_PROCESSING_SECONDS));

        for (CrashEvent event : stuck) {
            if (isTooOld(event, now)) {
                expire(event);
                continue;
            }
            // Put it back to RECEIVED. Only one caller can win this move.
            if (crashEventRepository.transition(event.getId(),
                    CrashEventStatus.PROCESSING, CrashEventStatus.RECEIVED) == 0) {
                continue;
            }
            log.warn("Sweep: crash {} was stuck in PROCESSING — trying again", event.getId());
            finish(event);
        }
    }

    private void finish(CrashEvent event) {
        try {
            ingestionService.onCancellationWindowExpired(event.getId());
        } catch (Exception ex) {
            log.error("Sweep failed for crash {}: {}", event.getId(), ex.getMessage(), ex);
        }
    }

    private void expire(CrashEvent event) {
        log.warn("Sweep: crash {} is too old to dispatch (deadline {}) — closing it", event.getId(),
                event.getCancellationDeadline());
        try {
            ingestionService.expireStaleCrash(event.getId());
        } catch (Exception ex) {
            log.error("Sweep could not close old crash {}: {}", event.getId(), ex.getMessage(), ex);
        }
    }

    private boolean isTooOld(CrashEvent event, Instant now) {
        return event.getCancellationDeadline() != null
                && event.getCancellationDeadline().isBefore(now.minus(MAX_AGE));
    }
}
