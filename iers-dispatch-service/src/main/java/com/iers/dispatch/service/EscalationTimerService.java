package com.iers.dispatch.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.*;

/**
 * The live 30-second timer for one open offer.
 *
 * The timer lives in memory, so a restart loses it. That is fine: DispatchSweepService
 * finds offers whose time is over (dispatch_expires_at in the database) and finishes them.
 * The sweep asks isTimerPending() so it never fights a timer that is still alive.
 */
@Slf4j
@Service
public class EscalationTimerService {

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(4, r -> {
                Thread t = new Thread(r, "escalation-timer");
                t.setDaemon(true);
                return t;
            });

    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> pendingTimers = new ConcurrentHashMap<>();

    public void startEscalationTimer(UUID incidentId, long delaySeconds, Runnable onEscalation) {
        // Never leave two timers running for one incident.
        ScheduledFuture<?> old = pendingTimers.remove(incidentId);
        if (old != null) {
            old.cancel(false);
        }

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            pendingTimers.remove(incidentId);
            log.info("Escalation timer expired for incident={}", incidentId);
            try {
                onEscalation.run();
            } catch (Exception e) {
                // A scheduled task that throws would vanish silently. The sweep will pick it up.
                log.error("Escalation handler failed for incident={}: {}", incidentId, e.getMessage(), e);
            }
        }, delaySeconds, TimeUnit.SECONDS);

        pendingTimers.put(incidentId, future);
        log.info("Started {}-second escalation timer for incident={}", delaySeconds, incidentId);
    }

    public boolean cancelTimer(UUID incidentId) {
        ScheduledFuture<?> future = pendingTimers.remove(incidentId);
        if (future != null) {
            boolean cancelled = future.cancel(false);
            log.info("Escalation timer cancelled for incident={}: {}", incidentId, cancelled);
            return cancelled;
        }
        return false;
    }

    /** True while a live timer exists for this incident. */
    public boolean isTimerPending(UUID incidentId) {
        ScheduledFuture<?> future = pendingTimers.get(incidentId);
        return future != null && !future.isDone();
    }

    @PreDestroy
    public void shutdown() { scheduler.shutdownNow(); }
}
