package com.iers.dispatch.service;

import com.iers.dispatch.config.NotifyProperties;
import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.entity.enums.IncidentStatus;
import com.iers.dispatch.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Restart safety and retries for Dispatch. Runs every 5 seconds and does three jobs:
 *
 *  1. OVERDUE OFFERS: an offer whose 30 seconds are over but whose live timer is gone
 *     (the service was restarted). The sweep ends the offer and moves to the next responder.
 *  2. RETRIES: an incident waiting for a responder (nobody was free, or the service stopped
 *     between two steps). The sweep tries again when next_retry_at has passed.
 *  3. CONTACT ALERTS: an active incident whose emergency contacts have not all been told yet
 *     (first try not started, an SMS failed, Auth was down, or the service restarted). The work
 *     is handed to the background pool, so a slow SMS gateway never delays jobs 1 and 2.
 *     This job does not care whether an ambulance was found.
 *
 * The 2-second grace stops the sweep from racing the normal timer. If both ever act at the
 * same moment, the conditional update in IncidentRepository lets only one of them win.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchSweepService {

    static final long GRACE_SECONDS = 2;

    private final IncidentRepository incidentRepository;
    private final EscalationTimerService timerService;
    private final DispatchOrchestrator orchestrator;
    private final NotificationService notificationService;
    private final NotifyProperties notifyProps;

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void sweep() {
        sweepOverdueOffers();
        sweepRetries();
        sweepContactAlerts();
    }

    void sweepOverdueOffers() {
        Instant cutoff = Instant.now().minusSeconds(GRACE_SECONDS);
        List<Incident> overdue = incidentRepository
                .findByStatusAndDispatchExpiresAtBefore(IncidentStatus.DISPATCHED, cutoff);

        for (Incident incident : overdue) {
            if (timerService.isTimerPending(incident.getId()) || incident.getAssignedResponderId() == null) {
                continue; // the live timer is still alive and will handle it
            }
            log.warn("Sweep: ending overdue offer for incident {} (expired {})",
                    incident.getId(), incident.getDispatchExpiresAt());
            try {
                orchestrator.onOfferTimeout(incident.getId(), incident.getAssignedResponderId());
            } catch (Exception ex) {
                log.error("Sweep failed for incident {}: {}", incident.getId(), ex.getMessage(), ex);
            }
        }
    }

    void sweepRetries() {
        List<Incident> waiting = incidentRepository
                .findByStatusAndNextRetryAtBefore(IncidentStatus.DISPATCHING, Instant.now());

        for (Incident incident : waiting) {
            log.info("Sweep: trying again to find a responder for incident {}", incident.getId());
            try {
                orchestrator.retryDispatch(incident.getId());
            } catch (Exception ex) {
                log.error("Retry failed for incident {}: {}", incident.getId(), ex.getMessage(), ex);
            }
        }
    }

    void sweepContactAlerts() {
        Instant now = Instant.now();
        List<Incident> due = incidentRepository.findContactAlertsDue(
                IncidentService.ACTIVE, now, now.minus(notifyProps.giveUpAfter()),
                PageRequest.of(0, Math.max(1, notifyProps.batchSize())));

        for (Incident incident : due) {
            log.info("Sweep: contacts of incident {} still have to be alerted", incident.getId());
            try {
                notificationService.crashDetected(incident.getId());
            } catch (Exception ex) {
                log.error("Could not queue the contact alert for incident {}: {}",
                        incident.getId(), ex.getMessage(), ex);
            }
        }
    }
}
