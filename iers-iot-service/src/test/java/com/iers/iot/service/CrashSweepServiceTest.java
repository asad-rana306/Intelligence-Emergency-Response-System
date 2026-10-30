package com.iers.iot.service;

import com.iers.iot.entity.CrashEvent;
import com.iers.iot.entity.enums.CrashEventStatus;
import com.iers.iot.repository.CrashEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CrashSweepServiceTest {

    @Mock private CrashEventRepository crashEventRepository;
    @Mock private CrashTimerService timerService;
    @Mock private CrashIngestionService ingestionService;

    @InjectMocks private CrashSweepService sweepService;

    private CrashEvent overdue(UUID id) {
        return CrashEvent.builder().id(id).status(CrashEventStatus.RECEIVED)
                .cancellationDeadline(Instant.now().minusSeconds(30)).build();
    }

    @Test
    @DisplayName("sweep — finishes an overdue crash whose timer was lost")
    void sweep_finishesLostTimer() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findByStatusAndCancellationDeadlineBefore(eq(CrashEventStatus.RECEIVED), any()))
                .thenReturn(List.of(overdue(id)));
        when(timerService.isTimerPending(id)).thenReturn(false);

        sweepService.sweep();

        verify(ingestionService).onCancellationWindowExpired(id);
    }

    @Test
    @DisplayName("sweep — leaves a crash alone while its normal timer is still alive")
    void sweep_skipsLiveTimer() {
        UUID id = UUID.randomUUID();
        when(crashEventRepository.findByStatusAndCancellationDeadlineBefore(any(), any()))
                .thenReturn(List.of(overdue(id)));
        when(timerService.isTimerPending(id)).thenReturn(true);

        sweepService.sweep();

        verify(ingestionService, never()).onCancellationWindowExpired(any());
    }

    @Test
    @DisplayName("sweep — nothing overdue, nothing happens")
    void sweep_nothingOverdue() {
        when(crashEventRepository.findByStatusAndCancellationDeadlineBefore(any(), any())).thenReturn(List.of());

        sweepService.sweep();

        verifyNoInteractions(ingestionService);
    }

    @Test
    @DisplayName("sweep — one failing crash does not stop the others")
    void sweep_oneFailureDoesNotStopTheRest() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        when(crashEventRepository.findByStatusAndCancellationDeadlineBefore(any(), any()))
                .thenReturn(List.of(overdue(bad), overdue(good)));
        when(timerService.isTimerPending(any())).thenReturn(false);
        doThrow(new RuntimeException("boom")).when(ingestionService).onCancellationWindowExpired(bad);

        sweepService.sweep();

        verify(ingestionService).onCancellationWindowExpired(good);
    }

    @Test
    @DisplayName("sweep — only looks at deadlines older than the 2-second grace")
    void sweep_usesGrace() {
        when(crashEventRepository.findByStatusAndCancellationDeadlineBefore(any(), any())).thenReturn(List.of());
        Instant before = Instant.now();

        sweepService.sweep();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(crashEventRepository).findByStatusAndCancellationDeadlineBefore(
                eq(CrashEventStatus.RECEIVED), cutoff.capture());
        assertThat(Duration.between(cutoff.getValue(), before).toMillis())
                .isBetween(CrashSweepService.GRACE_SECONDS * 1000 - 500, CrashSweepService.GRACE_SECONDS * 1000 + 2000);
    }
}
