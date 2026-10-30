package com.iers.dispatch.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class EscalationTimerServiceTest {

    private final EscalationTimerService timerService = new EscalationTimerService();

    @AfterEach
    void tearDown() { timerService.shutdown(); }

    @Test
    @DisplayName("cancelTimer — prevents escalation callback")
    void cancelPreventsCallback() throws InterruptedException {
        AtomicBoolean fired = new AtomicBoolean(false);
        UUID id = UUID.randomUUID();

        timerService.startEscalationTimer(id, 1, () -> fired.set(true));
        boolean cancelled = timerService.cancelTimer(id);

        assertThat(cancelled).isTrue();
        Thread.sleep(1500);
        assertThat(fired.get()).isFalse();
    }

    @Test
    @DisplayName("cancelTimer — returns false for unknown ID")
    void cancelUnknown() {
        assertThat(timerService.cancelTimer(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("the callback runs after the delay, and the timer is no longer pending")
    void firesAfterDelay() throws InterruptedException {
        CountDownLatch fired = new CountDownLatch(1);
        UUID id = UUID.randomUUID();

        timerService.startEscalationTimer(id, 1, fired::countDown);
        assertThat(timerService.isTimerPending(id)).isTrue();

        assertThat(fired.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(timerService.isTimerPending(id)).isFalse();
    }

    @Test
    @DisplayName("isTimerPending — false for an unknown incident")
    void notPendingWhenUnknown() {
        assertThat(timerService.isTimerPending(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("starting a second timer for the same incident replaces the first")
    void secondTimerReplacesFirst() throws InterruptedException {
        AtomicBoolean firstFired = new AtomicBoolean(false);
        CountDownLatch secondFired = new CountDownLatch(1);
        UUID id = UUID.randomUUID();

        timerService.startEscalationTimer(id, 1, () -> firstFired.set(true));
        timerService.startEscalationTimer(id, 1, secondFired::countDown);

        assertThat(secondFired.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(firstFired.get()).isFalse();
    }

    @Test
    @DisplayName("a callback that throws does not break later timers")
    void throwingCallbackIsContained() throws InterruptedException {
        CountDownLatch later = new CountDownLatch(1);

        timerService.startEscalationTimer(UUID.randomUUID(), 1, () -> { throw new IllegalStateException("boom"); });
        timerService.startEscalationTimer(UUID.randomUUID(), 2, later::countDown);

        assertThat(later.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
