package com.iers.iot.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.*;

/**
 * Manages the 10-second cancellation window for each crash event.
 *
 * When a crash payload arrives, a timer is started. If the driver taps "I AM OK"
 * within 10 seconds, the timer is cancelled and the event is aborted.
 * If 10 seconds pass with no cancellation, the onExpiry callback fires,
 * which triggers ML evaluation and Kafka publication.
 */
@Slf4j
@Service
public class CrashTimerService {

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(4, r -> {
                Thread t = new Thread(r, "crash-timer");
                t.setDaemon(true);
                return t;
            });

    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> pendingTimers =
            new ConcurrentHashMap<>();

    /**
     * Start a 10-second countdown for the given crash event.
     *
     * @param crashEventId unique identifier for the crash
     * @param onExpiry     callback to execute if the timer expires without cancellation
     */
    public void startCancellationWindow(UUID crashEventId, Runnable onExpiry) {
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            pendingTimers.remove(crashEventId);
            log.info("Cancellation window expired for crashEventId={}", crashEventId);
            onExpiry.run();
        }, 10, TimeUnit.SECONDS);

        pendingTimers.put(crashEventId, future);
        log.info("Started 10-second cancellation window for crashEventId={}", crashEventId);
    }

    /**
     * Cancel the timer for a crash event (driver tapped "I AM OK").
     *
     * @return true if the timer was successfully cancelled, false if it already fired or didn't exist
     */
    public boolean cancelTimer(UUID crashEventId) {
        ScheduledFuture<?> future = pendingTimers.remove(crashEventId);
        if (future != null) {
            boolean cancelled = future.cancel(false);
            log.info("Timer cancel for crashEventId={}: {}", crashEventId,
                    cancelled ? "SUCCESS" : "ALREADY_FIRED");
            return cancelled;
        }
        log.warn("No pending timer found for crashEventId={}", crashEventId);
        return false;
    }

    /**
     * Check if a timer is still pending (window still open).
     */
    public boolean isTimerPending(UUID crashEventId) {
        ScheduledFuture<?> future = pendingTimers.get(crashEventId);
        return future != null && !future.isDone();
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
