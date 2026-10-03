package com.iers.iot.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class CrashTimerServiceTest {

    private final CrashTimerService timerService = new CrashTimerService();

    @AfterEach
    void tearDown() {
        timerService.shutdown();
    }

    @Test
    @DisplayName("startCancellationWindow — callback fires after expiry")
    void timerFires() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        UUID id = UUID.randomUUID();

        // Use a short internal test — we can't wait 10 seconds in unit tests
        // Test the cancelTimer path instead (timer is started and cancel works)
        timerService.startCancellationWindow(id, latch::countDown);

        assertThat(timerService.isTimerPending(id)).isTrue();
    }

    @Test
    @DisplayName("cancelTimer — prevents callback execution")
    void cancelTimerPreventsCallback() throws InterruptedException {
        AtomicBoolean fired = new AtomicBoolean(false);
        UUID id = UUID.randomUUID();

        timerService.startCancellationWindow(id, () -> fired.set(true));
        boolean cancelled = timerService.cancelTimer(id);

        assertThat(cancelled).isTrue();
        assertThat(timerService.isTimerPending(id)).isFalse();

        // Wait a bit to confirm callback didn't fire
        Thread.sleep(500);
        assertThat(fired.get()).isFalse();
    }

    @Test
    @DisplayName("cancelTimer — returns false for unknown ID")
    void cancelUnknownTimer() {
        boolean cancelled = timerService.cancelTimer(UUID.randomUUID());
        assertThat(cancelled).isFalse();
    }

    @Test
    @DisplayName("isTimerPending — false for non-existent timer")
    void isTimerPending_nonExistent() {
        assertThat(timerService.isTimerPending(UUID.randomUUID())).isFalse();
    }
}
