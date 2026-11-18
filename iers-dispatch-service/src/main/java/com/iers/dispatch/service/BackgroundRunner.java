package com.iers.dispatch.service;

import com.iers.dispatch.config.NotifyProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small, bounded pool for slow side work: SMS and push.
 *
 * Why: sending an SMS can take many seconds. It must never run inside the dispatch transaction
 * or on the timer/sweep thread, because that would delay the ambulance.
 *
 * Bounded on purpose: when the queue is full the task is refused (and logged) instead of piling
 * up until memory runs out. Everything that goes through here is either retried by the sweep
 * (contact alerts) or best effort (push, follow-up SMS).
 *
 * It is a plain component, not an Executor bean, so Spring Boot's own task executor stays as it is.
 */
@Slf4j
@Component
public class BackgroundRunner {

    private final ThreadPoolExecutor pool;

    public BackgroundRunner(NotifyProperties props) {
        int threads = Math.max(1, props.executor().threads());
        int queue = Math.max(1, props.executor().queueCapacity());
        AtomicInteger counter = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queue),
                task -> {
                    Thread t = new Thread(task, "notify-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.pool.allowCoreThreadTimeOut(true);
    }

    /**
     * @param what a short name for the log
     * @return true if the task was accepted, false if the queue is full
     */
    public boolean run(String what, Runnable task) {
        try {
            pool.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.error("Background task '{}' failed: {}", what, e.getMessage(), e);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("Background queue is full - '{}' was not started", what);
            return false;
        }
    }

    @PreDestroy
    public void stop() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
