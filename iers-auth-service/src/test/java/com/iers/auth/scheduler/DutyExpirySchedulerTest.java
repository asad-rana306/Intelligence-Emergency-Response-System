package com.iers.auth.scheduler;

import com.iers.auth.config.DutyProperties;
import com.iers.auth.service.DutyExpiryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DutyExpirySchedulerTest {

    @Mock private DutyExpiryService expiryService;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;

    private final DutyProperties props = new DutyProperties(
            Duration.ofHours(1), Duration.ofMinutes(2), Duration.ofSeconds(25),
            new DutyProperties.Events(false, "duty-events"));

    private DutyExpiryScheduler scheduler() {
        return new DutyExpiryScheduler(expiryService, redis, props);
    }

    @Test
    @DisplayName("lock won — the sweep runs")
    void lockWon_runs() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq(DutyExpiryScheduler.LOCK_KEY), eq("1"), eq(Duration.ofSeconds(25))))
                .thenReturn(true);

        scheduler().sweep();

        verify(expiryService).expireDue();
    }

    @Test
    @DisplayName("lock held by another instance — this instance skips")
    void lockHeld_skips() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq(DutyExpiryScheduler.LOCK_KEY), any(), any(Duration.class)))
                .thenReturn(false);

        scheduler().sweep();

        verifyNoInteractions(expiryService);
    }

    @Test
    @DisplayName("Redis down — the sweep still runs (the database update is safe by itself)")
    void redisDown_stillRuns() {
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        scheduler().sweep();

        verify(expiryService).expireDue();
    }

    @Test
    @DisplayName("a failing sweep does not kill the timer")
    void failingSweep_doesNotThrow() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        when(expiryService.expireDue()).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> scheduler().sweep()).doesNotThrowAnyException();
    }
}
