package com.iers.dispatch.service;

import com.iers.dispatch.config.NotifyProperties;
import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.entity.enums.IncidentStatus;
import com.iers.dispatch.repository.IncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DispatchSweepServiceTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private EscalationTimerService timerService;
    @Mock private DispatchOrchestrator orchestrator;
    @Mock private NotificationService notificationService;

    private DispatchSweepService sweepService;

    @BeforeEach
    void setUp() {
        NotifyProperties props = new NotifyProperties("http://t", "1122", Duration.ofSeconds(20),
                Duration.ofMinutes(2), Duration.ofMinutes(30), 20, new NotifyProperties.Executor(1, 10));
        sweepService = new DispatchSweepService(incidentRepository, timerService, orchestrator,
                notificationService, props);
    }

    private Incident unalerted(UUID id, IncidentStatus status) {
        return Incident.builder().id(id).crashEventId(UUID.randomUUID()).status(status)
                .createdAt(Instant.now().minusSeconds(40)).build();
    }

    private final UUID responderId = UUID.randomUUID();

    private Incident offered(UUID id, UUID assigned) {
        return Incident.builder().id(id).crashEventId(UUID.randomUUID()).status(IncidentStatus.DISPATCHED)
                .assignedResponderId(assigned).dispatchExpiresAt(Instant.now().minusSeconds(60)).build();
    }

    private Incident waiting(UUID id) {
        return Incident.builder().id(id).crashEventId(UUID.randomUUID()).status(IncidentStatus.DISPATCHING)
                .nextRetryAt(Instant.now().minusSeconds(5)).build();
    }

    @Test
    @DisplayName("Overdue offer whose timer was lost (restart): the sweep ends it")
    void overdueOffer_noTimer_isFinished() {
        UUID id = UUID.randomUUID();
        when(incidentRepository.findByStatusAndDispatchExpiresAtBefore(eq(IncidentStatus.DISPATCHED), any(Instant.class)))
                .thenReturn(List.of(offered(id, responderId)));
        when(timerService.isTimerPending(id)).thenReturn(false);

        sweepService.sweepOverdueOffers();

        verify(orchestrator).onOfferTimeout(id, responderId);
    }

    @Test
    @DisplayName("Overdue offer whose live timer is still running: the sweep leaves it alone")
    void overdueOffer_timerAlive_isSkipped() {
        UUID id = UUID.randomUUID();
        when(incidentRepository.findByStatusAndDispatchExpiresAtBefore(eq(IncidentStatus.DISPATCHED), any(Instant.class)))
                .thenReturn(List.of(offered(id, responderId)));
        when(timerService.isTimerPending(id)).thenReturn(true);

        sweepService.sweepOverdueOffers();

        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("Overdue offer with nobody assigned is skipped")
    void overdueOffer_noAssignee_isSkipped() {
        UUID id = UUID.randomUUID();
        when(incidentRepository.findByStatusAndDispatchExpiresAtBefore(eq(IncidentStatus.DISPATCHED), any(Instant.class)))
                .thenReturn(List.of(offered(id, null)));
        when(timerService.isTimerPending(id)).thenReturn(false);

        sweepService.sweepOverdueOffers();

        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("One failing offer does not stop the others")
    void overdueOffer_failureContinues() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(incidentRepository.findByStatusAndDispatchExpiresAtBefore(eq(IncidentStatus.DISPATCHED), any(Instant.class)))
                .thenReturn(List.of(offered(first, responderId), offered(second, responderId)));
        when(timerService.isTimerPending(any())).thenReturn(false);
        doThrow(new RuntimeException("db hiccup")).when(orchestrator).onOfferTimeout(first, responderId);

        sweepService.sweepOverdueOffers();

        verify(orchestrator).onOfferTimeout(second, responderId);
    }

    @Test
    @DisplayName("Incidents waiting for a responder are retried")
    void retries_run() {
        UUID id = UUID.randomUUID();
        when(incidentRepository.findByStatusAndNextRetryAtBefore(eq(IncidentStatus.DISPATCHING), any(Instant.class)))
                .thenReturn(List.of(waiting(id)));

        sweepService.sweepRetries();

        verify(orchestrator).retryDispatch(id);
    }

    @Test
    @DisplayName("One failing retry does not stop the others")
    void retries_failureContinues() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(incidentRepository.findByStatusAndNextRetryAtBefore(eq(IncidentStatus.DISPATCHING), any(Instant.class)))
                .thenReturn(List.of(waiting(first), waiting(second)));
        doThrow(new RuntimeException("db hiccup")).when(orchestrator).retryDispatch(first);

        sweepService.sweepRetries();

        verify(orchestrator).retryDispatch(second);
    }

    @Test
    @DisplayName("Contact alerts: every incident whose contacts are not all told is handed to the notifier")
    void contactAlerts_queued() {
        UUID dispatching = UUID.randomUUID();
        UUID noAmbulance = UUID.randomUUID();
        when(incidentRepository.findContactAlertsDue(eq(IncidentService.ACTIVE), any(Instant.class),
                any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(unalerted(dispatching, IncidentStatus.DISPATCHED),
                        unalerted(noAmbulance, IncidentStatus.DISPATCHING)));

        sweepService.sweepContactAlerts();

        verify(notificationService).crashDetected(dispatching);
        verify(notificationService).crashDetected(noAmbulance);
    }

    @Test
    @DisplayName("Contact alerts: the sweep reads only incidents younger than give-up-after, one batch at a time")
    void contactAlerts_windowAndBatch() {
        when(incidentRepository.findContactAlertsDue(any(), any(), any(), any())).thenReturn(List.of());

        Instant before = Instant.now();
        sweepService.sweepContactAlerts();

        org.mockito.ArgumentCaptor<Instant> notBefore = org.mockito.ArgumentCaptor.forClass(Instant.class);
        org.mockito.ArgumentCaptor<Pageable> page = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(incidentRepository).findContactAlertsDue(eq(IncidentService.ACTIVE), any(Instant.class),
                notBefore.capture(), page.capture());
        org.assertj.core.api.Assertions.assertThat(notBefore.getValue())
                .isBetween(before.minus(Duration.ofMinutes(30)).minusSeconds(1),
                        Instant.now().minus(Duration.ofMinutes(30)).plusSeconds(1));
        org.assertj.core.api.Assertions.assertThat(page.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("Contact alerts: one failing incident does not stop the others")
    void contactAlerts_failureContinues() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(incidentRepository.findContactAlertsDue(any(), any(), any(), any()))
                .thenReturn(List.of(unalerted(first, IncidentStatus.DISPATCHING),
                        unalerted(second, IncidentStatus.DISPATCHING)));
        doThrow(new RuntimeException("boom")).when(notificationService).crashDetected(first);

        sweepService.sweepContactAlerts();

        verify(notificationService).crashDetected(second);
    }

    @Test
    @DisplayName("sweep() does all three jobs")
    void sweep_doesBoth() {
        UUID offeredId = UUID.randomUUID();
        UUID waitingId = UUID.randomUUID();
        when(incidentRepository.findByStatusAndDispatchExpiresAtBefore(eq(IncidentStatus.DISPATCHED), any(Instant.class)))
                .thenReturn(List.of(offered(offeredId, responderId)));
        when(timerService.isTimerPending(offeredId)).thenReturn(false);
        when(incidentRepository.findByStatusAndNextRetryAtBefore(eq(IncidentStatus.DISPATCHING), any(Instant.class)))
                .thenReturn(List.of(waiting(waitingId)));
        UUID unalertedId = UUID.randomUUID();
        when(incidentRepository.findContactAlertsDue(any(), any(), any(), any()))
                .thenReturn(List.of(unalerted(unalertedId, IncidentStatus.DISPATCHING)));

        sweepService.sweep();

        verify(orchestrator).onOfferTimeout(offeredId, responderId);
        verify(orchestrator).retryDispatch(waitingId);
        verify(notificationService).crashDetected(unalertedId);
    }
}
