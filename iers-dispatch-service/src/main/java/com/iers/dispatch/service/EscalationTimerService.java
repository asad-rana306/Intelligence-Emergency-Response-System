package com.iers.dispatch.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.*;

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

    public void startEscalationTimer(UUID incidentId, Runnable onEscalation) {
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            pendingTimers.remove(incidentId);
            log.info("Escalation timer expired for incident={}", incidentId);
            onEscalation.run();
        }, 30, TimeUnit.SECONDS);

        pendingTimers.put(incidentId, future);
        log.info("Started 30-second escalation timer for incident={}", incidentId);
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

    @PreDestroy
    public void shutdown() { scheduler.shutdownNow(); }
}
