package com.iers.dispatch.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
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

        timerService.startEscalationTimer(id, () -> fired.set(true));
        boolean cancelled = timerService.cancelTimer(id);

        assertThat(cancelled).isTrue();
        Thread.sleep(500);
        assertThat(fired.get()).isFalse();
    }

    @Test
    @DisplayName("cancelTimer — returns false for unknown ID")
    void cancelUnknown() {
        assertThat(timerService.cancelTimer(UUID.randomUUID())).isFalse();
    }
}
