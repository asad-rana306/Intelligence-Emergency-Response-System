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

    private CrashEvent crash(UUID id, CrashEventStatus status, long deadlineSecondsAgo) {
        return CrashEvent.builder().id(id).status(status)
                .cancellationDeadline(Instant.now().minusSeconds(deadlineSecondsAgo)).build();
    }

    private void received(CrashEvent... events) {
        when(crashEventRepository.findByStatusAndCancellationDeadlineBefore(eq(CrashEventStatus.RECEIVED), any()))
                .thenReturn(List.of(events));
    }

    private void processing(CrashEvent... events) {
        when(crashEventRepository.findByStatusAndCancellationDeadlineBefore(eq(CrashEventStatus.PROCESSING), any()))
                .thenReturn(List.of(events));
    }

    // ══════════════════ RECEIVED, timer lost ══════════════════

    @Test
    @DisplayName("sweep — finishes an overdue crash whose timer was lost")
    void sweep_finishesLostTimer() {
        UUID id = UUID.randomUUID();
        received(crash(id, CrashEventStatus.RECEIVED, 30));
        when(timerService.isTimerPending(id)).thenReturn(false);

        sweepService.sweep();

        verify(ingestionService).onCancellationWindowExpired(id);
    }

    @Test
    @DisplayName("sweep — leaves a crash alone while its normal timer is still alive")
    void sweep_skipsLiveTimer() {
        UUID id = UUID.randomUUID();
        received(crash(id, CrashEventStatus.RECEIVED, 30));
        when(timerService.isTimerPending(id)).thenReturn(true);

        sweepService.sweep();

        verify(ingestionService, never()).onCancellationWindowExpired(any());
    }

    @Test
    @DisplayName("sweep — nothing overdue, nothing happens")
    void sweep_nothingOverdue() {
        sweepService.sweep();

        verifyNoInteractions(ingestionService);
        verify(crashEventRepository, never()).transition(any(), any(), any());
    }

    @Test
    @DisplayName("sweep — one failing crash does not stop the others")
    void sweep_oneFailureDoesNotStopTheRest() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        received(crash(bad, CrashEventStatus.RECEIVED, 30), crash(good, CrashEventStatus.RECEIVED, 30));
        when(timerService.isTimerPending(any())).thenReturn(false);
        doThrow(new RuntimeException("boom")).when(ingestionService).onCancellationWindowExpired(bad);

        sweepService.sweep();

        verify(ingestionService).onCancellationWindowExpired(good);
    }

    @Test
    @DisplayName("sweep — only looks at deadlines older than the 2-second grace")
    void sweep_usesGrace() {
        Instant before = Instant.now();

        sweepService.sweep();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(crashEventRepository).findByStatusAndCancellationDeadlineBefore(
                eq(CrashEventStatus.RECEIVED), cutoff.capture());
        assertThat(Duration.between(cutoff.getValue(), before).toMillis())
                .isBetween(CrashSweepService.GRACE_SECONDS * 1000 - 500, CrashSweepService.GRACE_SECONDS * 1000 + 2000);
    }

    // ══════════════════ PROCESSING, stuck ══════════════════

    @Test
    @DisplayName("sweep — a crash stuck in PROCESSING is put back and tried again")
    void sweep_retriesStuckProcessing() {
        UUID id = UUID.randomUUID();
        processing(crash(id, CrashEventStatus.PROCESSING, 300));
        when(crashEventRepository.transition(id, CrashEventStatus.PROCESSING, CrashEventStatus.RECEIVED)).thenReturn(1);

        sweepService.sweep();

        verify(ingestionService).onCancellationWindowExpired(id);
    }

    @Test
    @DisplayName("sweep — stuck crash that someone else already moved is left alone")
    void sweep_stuckAlreadyMoved() {
        UUID id = UUID.randomUUID();
        processing(crash(id, CrashEventStatus.PROCESSING, 300));
        when(crashEventRepository.transition(id, CrashEventStatus.PROCESSING, CrashEventStatus.RECEIVED)).thenReturn(0);

        sweepService.sweep();

        verify(ingestionService, never()).onCancellationWindowExpired(any());
    }

    @Test
    @DisplayName("sweep — only PROCESSING crashes older than a minute count as stuck")
    void sweep_stuckUsesOneMinuteCutoff() {
        Instant before = Instant.now();

        sweepService.sweep();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(crashEventRepository).findByStatusAndCancellationDeadlineBefore(
                eq(CrashEventStatus.PROCESSING), cutoff.capture());
        assertThat(Duration.between(cutoff.getValue(), before).toMillis())
                .isBetween(CrashSweepService.STUCK_PROCESSING_SECONDS * 1000 - 500,
                        CrashSweepService.STUCK_PROCESSING_SECONDS * 1000 + 2000);
    }

    @Test
    @DisplayName("sweep — a failing retry does not stop the sweep")
    void sweep_stuckRetryFailureIsCaught() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        processing(crash(bad, CrashEventStatus.PROCESSING, 300), crash(good, CrashEventStatus.PROCESSING, 300));
        when(crashEventRepository.transition(any(), eq(CrashEventStatus.PROCESSING), eq(CrashEventStatus.RECEIVED)))
                .thenReturn(1);
        doThrow(new RuntimeException("boom")).when(ingestionService).onCancellationWindowExpired(bad);

        sweepService.sweep();

        verify(ingestionService).onCancellationWindowExpired(good);
    }

    // ══════════════════ too old ══════════════════

    @Test
    @DisplayName("sweep — a RECEIVED crash older than 30 minutes is closed, not dispatched")
    void sweep_oldReceivedIsExpired() {
        UUID id = UUID.randomUUID();
        received(crash(id, CrashEventStatus.RECEIVED, 2 * 60 * 60));

        sweepService.sweep();

        verify(ingestionService).expireStaleCrash(id);
        verify(ingestionService, never()).onCancellationWindowExpired(any());
    }

    @Test
    @DisplayName("sweep — a PROCESSING crash older than 30 minutes is closed, not retried")
    void sweep_oldProcessingIsExpired() {
        UUID id = UUID.randomUUID();
        processing(crash(id, CrashEventStatus.PROCESSING, 2 * 60 * 60));

        sweepService.sweep();

        verify(ingestionService).expireStaleCrash(id);
        verify(ingestionService, never()).onCancellationWindowExpired(any());
        verify(crashEventRepository, never()).transition(any(), any(), any());
    }
}
