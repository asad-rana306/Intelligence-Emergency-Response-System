package com.iers.dispatch.service;

import com.iers.dispatch.dto.feign.EmergencyContactDto;
import com.iers.dispatch.dto.feign.MedicalProfileDto;
import com.iers.dispatch.dto.kafka.CrashEventMessage;
import com.iers.dispatch.dto.response.DispatchAckResponse;
import com.iers.dispatch.entity.DispatchAttempt;
import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.entity.ResponderLocation;
import com.iers.dispatch.entity.enums.DispatchResponseType;
import com.iers.dispatch.entity.enums.IncidentStatus;
import com.iers.dispatch.exception.IncidentNotFoundException;
import com.iers.dispatch.feign.AuthServiceClient;
import com.iers.dispatch.repository.DispatchAttemptRepository;
import com.iers.dispatch.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchOrchestrator {

    private final IncidentRepository incidentRepository;
    private final DispatchAttemptRepository attemptRepository;
    private final AuthServiceClient authServiceClient;
    private final SpatialQueryService spatialQueryService;
    private final EscalationTimerService escalationTimerService;
    private final NotificationService notificationService;
    private final GpsStreamingService gpsStreamingService;

    // ═══════════════════════════════════════════════════
    //  CRASH_DETECTED — create incident and dispatch
    // ═══════════════════════════════════════════════════

    @Transactional
    public void handleCrashDetected(CrashEventMessage event) {
        log.info("Processing CRASH_DETECTED: crashEventId={}, priority={}",
                event.getCrashEventId(), event.getPriorityScore());

        UUID driverId = event.getDriverId() != null ? UUID.fromString(event.getDriverId()) : null;
        UUID crashEventId = UUID.fromString(event.getCrashEventId());

        // ── Fetch medical profile from Auth (circuit-broken) ──
        MedicalProfileDto medProfile = driverId != null
                ? authServiceClient.getMedicalProfile(driverId)
                : fallbackProfile();

        // ── Fetch emergency contacts (circuit-broken) ──
        List<EmergencyContactDto> contacts = driverId != null
                ? authServiceClient.getEmergencyContacts(driverId)
                : Collections.emptyList();

        // ── Create incident ──
        Incident incident = Incident.builder()
                .crashEventId(crashEventId)
                .driverId(driverId)
                .driverName(event.getDriverName())
                .driverPhone(event.getDriverPhone())
                .priorityScore(event.getPriorityScore())
                .gpsLat(event.getGpsLat())
                .gpsLng(event.getGpsLng())
                .status(IncidentStatus.DISPATCHING)
                .bloodType(medProfile.getBloodType())
                .allergies(medProfile.getAllergies())
                .build();

        incident = incidentRepository.save(incident);
        log.info("Incident created: id={}", incident.getId());

        // ── Send SMS to emergency contacts ──
        if (!contacts.isEmpty()) {
            notificationService.sendEmergencySms(contacts, event.getDriverName(), incident.getId());
        }

        // ── Dispatch to nearest responder ──
        dispatchToNearest(incident, Collections.emptyList());
    }

    // ═══════════════════════════════════════════════════
    //  CRASH_CANCELLED — stand down and notify
    // ═══════════════════════════════════════════════════

    @Transactional
    public void handleCrashCancelled(CrashEventMessage event) {
        log.info("Processing CRASH_CANCELLED: crashEventId={}, reason={}",
                event.getCrashEventId(), event.getReason());

        UUID crashEventId = UUID.fromString(event.getCrashEventId());
        Incident incident = incidentRepository.findByCrashEventId(crashEventId).orElse(null);

        if (incident == null) {
            log.info("No incident found for cancelled crash {} — may not have been dispatched yet",
                    crashEventId);
            return;
        }

        // Cancel escalation timer
        escalationTimerService.cancelTimer(incident.getId());

        // Stand down assigned responder
        if (incident.getAssignedResponderId() != null) {
            notificationService.sendStandDownPush(incident.getAssignedResponderId(), incident.getId());
            spatialQueryService.markResponderOnDuty(incident.getAssignedResponderId());
            try {
                authServiceClient.updateResponderStatus(incident.getAssignedResponderId(), "ON_DUTY");
            } catch (Exception e) {
                log.warn("Could not reset responder status via Auth: {}", e.getMessage());
            }
        }

        // Send false alarm SMS to emergency contacts
        UUID driverId = incident.getDriverId();
        if (driverId != null) {
            List<EmergencyContactDto> contacts = authServiceClient.getEmergencyContacts(driverId);
            if (!contacts.isEmpty()) {
                notificationService.sendFalseAlarmSms(contacts, incident.getDriverName());
            }
        }

        // Close the incident
        incident.setStatus(IncidentStatus.CANCELLED_BY_USER);
        incident.setResolvedAt(Instant.now());
        incidentRepository.save(incident);
        gpsStreamingService.cleanupIncident(incident.getId().toString());

        log.info("Incident {} cancelled by user", incident.getId());
    }

    // ═══════════════════════════════════════════════════
    //  RESPONDER ACTIONS
    // ═══════════════════════════════════════════════════

    @Transactional
    public DispatchAckResponse acceptDispatch(UUID incidentId, String responderId) {
        Incident incident = findIncident(incidentId);
        UUID responderUuid = UUID.fromString(responderId);

        escalationTimerService.cancelTimer(incidentId);

        incident.setStatus(IncidentStatus.ACCEPTED);
        incident.setAssignedResponderId(responderUuid);
        incident.setAcceptedAt(Instant.now());
        incidentRepository.save(incident);

        // Mark the dispatch attempt as ACCEPTED
        markAttemptResponse(incidentId, responderUuid, DispatchResponseType.ACCEPTED);

        // Update responder status to ON_MISSION
        spatialQueryService.markResponderOnMission(responderUuid);
        try {
            authServiceClient.updateResponderStatus(responderUuid, "ON_MISSION");
        } catch (Exception e) {
            log.warn("Could not update responder status in Auth: {}", e.getMessage());
        }

        log.info("Dispatch ACCEPTED: incident={}, responder={}", incidentId, responderId);

        return DispatchAckResponse.builder()
                .incidentId(incidentId.toString())
                .status("ACCEPTED")
                .message("Dispatch accepted. GPS streaming enabled.")
                .build();
    }

    @Transactional
    public DispatchAckResponse rejectDispatch(UUID incidentId, String responderId) {
        Incident incident = findIncident(incidentId);
        UUID responderUuid = UUID.fromString(responderId);

        escalationTimerService.cancelTimer(incidentId);
        markAttemptResponse(incidentId, responderUuid, DispatchResponseType.REJECTED);

        log.info("Dispatch REJECTED by responder={} for incident={}", responderId, incidentId);

        // Immediately escalate to next responder
        List<UUID> excludedIds = getAttemptedResponderIds(incidentId);
        dispatchToNearest(incident, excludedIds);

        return DispatchAckResponse.builder()
                .incidentId(incidentId.toString())
                .status("REJECTED")
                .message("Dispatch rejected. Escalating to next responder.")
                .build();
    }

    @Transactional
    public DispatchAckResponse updateIncidentStatus(UUID incidentId, IncidentStatus newStatus,
                                                     String hospitalId) {
        Incident incident = findIncident(incidentId);

        switch (newStatus) {
            case ARRIVED -> {
                incident.setStatus(IncidentStatus.ARRIVED);
                incident.setArrivedAt(Instant.now());
            }
            case EN_ROUTE_HOSPITAL -> {
                incident.setStatus(IncidentStatus.EN_ROUTE_HOSPITAL);
                incident.setEnRouteAt(Instant.now());
                if (hospitalId != null) incident.setHospitalId(hospitalId);
                notificationService.sendHospitalPreAlert(
                        hospitalId, incidentId,
                        incident.getBloodType(), incident.getAllergies(), "Calculating...");
            }
            case RESOLVED -> {
                incident.setStatus(IncidentStatus.RESOLVED);
                incident.setResolvedAt(Instant.now());
                gpsStreamingService.cleanupIncident(incidentId.toString());
                if (incident.getAssignedResponderId() != null) {
                    spatialQueryService.markResponderOnDuty(incident.getAssignedResponderId());
                    try {
                        authServiceClient.updateResponderStatus(
                                incident.getAssignedResponderId(), "ON_DUTY");
                    } catch (Exception e) {
                        log.warn("Could not reset responder in Auth: {}", e.getMessage());
                    }
                }
            }
            default -> throw new IllegalArgumentException("Invalid status transition: " + newStatus);
        }

        incidentRepository.save(incident);
        log.info("Incident {} status → {}", incidentId, newStatus);

        return DispatchAckResponse.builder()
                .incidentId(incidentId.toString())
                .status(newStatus.name())
                .message("Incident status updated.")
                .build();
    }

    @Transactional
    public void markBystanderAssisting(UUID incidentId) {
        Incident incident = findIncident(incidentId);
        incident.setBystanderAssisting(true);
        incidentRepository.save(incident);
        log.info("Bystander assisting at incident={}", incidentId);
    }

    // ═══════════════════════════════════════════════════
    //  DISPATCH ENGINE
    // ═══════════════════════════════════════════════════

    void dispatchToNearest(Incident incident, List<UUID> excludedResponderIds) {
        ResponderLocation nearest = spatialQueryService.findNearestResponder(
                incident.getGpsLat(), incident.getGpsLng(), excludedResponderIds);

        if (nearest == null) {
            log.error("No available responders for incident={} — all exhausted", incident.getId());
            incident.setStatus(IncidentStatus.DISPATCHING);
            incidentRepository.save(incident);
            return;
        }

        // Create dispatch attempt
        int attemptNum = attemptRepository.countByIncidentId(incident.getId()) + 1;
        DispatchAttempt attempt = DispatchAttempt.builder()
                .incidentId(incident.getId())
                .responderId(nearest.getResponderId())
                .attemptNumber(attemptNum)
                .sentAt(Instant.now())
                .response(DispatchResponseType.PENDING)
                .build();
        attemptRepository.save(attempt);

        incident.setStatus(IncidentStatus.DISPATCHED);
        incident.setAssignedResponderId(nearest.getResponderId());
        incidentRepository.save(incident);

        // Send push notification
        notificationService.sendResponderPush(
                nearest.getResponderId(), nearest.getVehicleId(),
                incident.getId(), incident.getDriverName(),
                incident.getGpsLat(), incident.getGpsLng(),
                incident.getPriorityScore());

        // Start 30-second escalation timer
        final UUID incidentId = incident.getId();
        escalationTimerService.startEscalationTimer(incidentId, () -> onEscalationTimeout(incidentId));

        log.info("Dispatched to responder {} (attempt #{}), incident={}",
                nearest.getResponderId(), attemptNum, incidentId);
    }

    void onEscalationTimeout(UUID incidentId) {
        Incident incident = incidentRepository.findById(incidentId).orElse(null);
        if (incident == null || incident.getStatus() == IncidentStatus.ACCEPTED) {
            return; // Already accepted or resolved
        }

        // Mark current attempt as TIMED_OUT
        if (incident.getAssignedResponderId() != null) {
            markAttemptResponse(incidentId, incident.getAssignedResponderId(),
                    DispatchResponseType.TIMED_OUT);
        }

        log.info("Escalation triggered for incident={}", incidentId);

        List<UUID> excludedIds = getAttemptedResponderIds(incidentId);
        dispatchToNearest(incident, excludedIds);
    }

    // ═══════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════

    private Incident findIncident(UUID incidentId) {
        return incidentRepository.findById(incidentId)
                .orElseThrow(() -> new IncidentNotFoundException("Incident not found: " + incidentId));
    }

    private List<UUID> getAttemptedResponderIds(UUID incidentId) {
        return attemptRepository.findByIncidentIdOrderByAttemptNumber(incidentId).stream()
                .map(DispatchAttempt::getResponderId)
                .toList();
    }

    private void markAttemptResponse(UUID incidentId, UUID responderId, DispatchResponseType response) {
        attemptRepository.findByIncidentIdOrderByAttemptNumber(incidentId).stream()
                .filter(a -> a.getResponderId().equals(responderId)
                        && a.getResponse() == DispatchResponseType.PENDING)
                .findFirst()
                .ifPresent(a -> {
                    a.setResponse(response);
                    a.setRespondedAt(Instant.now());
                    attemptRepository.save(a);
                });
    }

    private MedicalProfileDto fallbackProfile() {
        return MedicalProfileDto.builder()
                .bloodType("UNKNOWN").allergies("UNKNOWN").medications("UNKNOWN")
                .chronicConditions("UNKNOWN").emergencyNotes("Profile unavailable").build();
    }
}
