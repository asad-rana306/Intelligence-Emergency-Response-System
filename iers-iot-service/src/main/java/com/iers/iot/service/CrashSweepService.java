package com.iers.iot.service;

import com.iers.iot.entity.CrashEvent;
import com.iers.iot.entity.enums.CrashEventStatus;
import com.iers.iot.repository.CrashEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Restart safety for the 10-second window.
 *
 * The countdown timer lives in memory, so a restart loses it and a crash would sit in RECEIVED
 * forever. Every 5 seconds this looks for crashes still RECEIVED whose deadline has passed and
 * finishes them (ML check, then CRASH_DETECTED or CRASH_DISMISSED).
 *
 * The 2-second grace stops it from racing the normal timer. If both ever fire together,
 * repository.transition() lets only one of them win.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrashSweepService {

    static final long GRACE_SECONDS = 2;

    private final CrashEventRepository crashEventRepository;
    private final CrashTimerService timerService;
    private final CrashIngestionService ingestionService;

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void sweep() {
        Instant cutoff = Instant.now().minusSeconds(GRACE_SECONDS);
        List<CrashEvent> overdue = crashEventRepository
                .findByStatusAndCancellationDeadlineBefore(CrashEventStatus.RECEIVED, cutoff);

        for (CrashEvent event : overdue) {
            if (timerService.isTimerPending(event.getId())) {
                continue; // the normal timer is still alive and will handle it
            }
            log.warn("Sweep: finishing crash {} whose timer was lost (deadline {})",
                    event.getId(), event.getCancellationDeadline());
            try {
                ingestionService.onCancellationWindowExpired(event.getId());
            } catch (Exception ex) {
                log.error("Sweep failed for crash {}: {}", event.getId(), ex.getMessage(), ex);
            }
        }
    }
}
