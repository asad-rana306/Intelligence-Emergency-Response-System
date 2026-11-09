package com.iers.dispatch.service;

import com.iers.dispatch.dto.Viewer;
import com.iers.dispatch.dto.feign.EmergencyContactDto;
import com.iers.dispatch.dto.feign.MedicalProfileDto;
import com.iers.dispatch.dto.kafka.CrashEventMessage;
import com.iers.dispatch.dto.kafka.IncidentEventMessage;
import com.iers.dispatch.dto.response.DispatchAckResponse;
import com.iers.dispatch.entity.DispatchAttempt;
import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.entity.ResponderLocation;
import com.iers.dispatch.entity.enums.DispatchResponseType;
import com.iers.dispatch.entity.enums.IncidentStatus;
import com.iers.dispatch.exception.IncidentAccessDeniedException;
import com.iers.dispatch.exception.IncidentNotFoundException;
import com.iers.dispatch.exception.InvalidIncidentStateException;
import com.iers.dispatch.feign.AuthServiceClient;
import com.iers.dispatch.kafka.IncidentEventPublisher;
import com.iers.dispatch.repository.DispatchAttemptRepository;
import com.iers.dispatch.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The brain of Dispatch.
 *
 * Rules (PHASE 3):
 *  - Every step is announced on Kafka topic "incident-events" (IncidentEventPublisher).
 *  - Only the OFFERED responder can accept or reject. Only the ASSIGNED responder can move the
 *    incident on. A cancelled or finished incident can never be accepted.
 *  - Every change of state is one conditional database update (see IncidentRepository), so a timer,
 *    the sweep, a driver cancel and a responder tap can never handle the same thing twice.
 *  - If nobody is free, Dispatch tries again every 15 seconds. After 2 minutes without an accept it
 *    announces NO_RESPONDER_AVAILABLE once, and keeps trying until the incident is 30 minutes old.
 *  - Responders who rejected are never asked again for the same incident. Responders who only let the
 *    offer time out may be asked again, but only after everybody who was not asked yet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchOrchestrator {

    static final String REASON_DRIVER_CANCELLED = "DRIVER_CANCELLED";

    private final IncidentRepository incidentRepository;
    private final DispatchAttemptRepository attemptRepository;
    private final AuthServiceClient authServiceClient;
    private final SpatialQueryService spatialQueryService;
    private final EscalationTimerService escalationTimerService;
    private final NotificationService notificationService;
    private final GpsStreamingService gpsStreamingService;
    private final IncidentEventPublisher eventPublisher;

    // Defaults are the contract values; application.yml may override them.
    @Value("${dispatch.offer-seconds:30}")
    private long offerSeconds = 30;

    @Value("${dispatch.retry-interval-seconds:15}")
    private long retryIntervalSeconds = 15;

    /** Safety net: if the service dies between two steps, the sweep resumes after this long. */
    @Value("${dispatch.safety-retry-seconds:10}")
    private long safetyRetrySeconds = 10;

    @Value("${dispatch.no-responder-after-seconds:120}")
    private long noResponderAfterSeconds = 120;

    @Value("${dispatch.give-up-minutes:30}")
    private long giveUpMinutes = 30;

    // ═══════════════════════════════════════════════════
    //  CRASH_DETECTED — create incident and dispatch
    // ═══════════════════════════════════════════════════

    @Transactional
    public void handleCrashDetected(CrashEventMessage event) {
        log.info("Processing CRASH_DETECTED: crashEventId={}, priority={}",
                event.getCrashEventId(), event.getPriorityScore());

        UUID crashEventId = UUID.fromString(event.getCrashEventId());

        // Kafka can deliver the same message twice. One crash = one incident.
        if (incidentRepository.findByCrashEventId(crashEventId).isPresent()) {
            log.info("Incident for crash {} already exists — ignoring repeated event", crashEventId);
            return;
        }

        UUID driverId = event.getDriverId() != null ? UUID.fromString(event.getDriverId()) : null;

        // One driver = one open incident. If help is already on the way for this driver, a second
        // severe crash is the same emergency (or a retry). It must not send a second ambulance.
        if (driverId != null && incidentRepository
                .findFirstByDriverIdAndStatusInOrderByCreatedAtDesc(driverId, IncidentService.ACTIVE).isPresent()) {
            log.warn("Driver {} already has an open incident — ignoring new crash {}", driverId, crashEventId);
            return;
        }

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
                .nextRetryAt(Instant.now().plusSeconds(safetyRetrySeconds))
                .build();

        incident = incidentRepository.save(incident);
        log.info("Incident created: id={}", incident.getId());

        // The driver's phone learns its incident ID at once.
        emit(IncidentEventMessage.INCIDENT_CREATED, incident, null,
                data("incidentId", incident.getId().toString(),
                        "crashEventId", crashEventId.toString()));

        // ── Send SMS to emergency contacts ──
        if (!contacts.isEmpty()) {
            notificationService.sendEmergencySms(contacts, event.getDriverName(), incident.getId());
        }

        // ── Dispatch to nearest responder ──
        dispatchToNearest(incident.getId());
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

        // Close it first. If it is already over (resolved), nothing below should happen.
        int closed = incidentRepository.cancelIfActive(incident.getId(), IncidentService.ACTIVE,
                IncidentStatus.CANCELLED_BY_USER, Instant.now());
        if (closed == 0) {
            log.info("Incident {} is already {} — cancel ignored", incident.getId(), incident.getStatus());
            return;
        }

        escalationTimerService.cancelTimer(incident.getId());

        // Stand down the offered/assigned responder
        UUID responderId = incident.getAssignedResponderId();
        if (responderId != null) {
            notificationService.sendStandDownPush(responderId, incident.getId());
            spatialQueryService.markResponderOnDuty(responderId);
            updateAuthStatus(responderId, "ON_DUTY");
            emit(IncidentEventMessage.INCIDENT_STAND_DOWN, incident, responderId,
                    data("incidentId", incident.getId().toString(), "reason", REASON_DRIVER_CANCELLED));
        }

        // Send false alarm SMS to emergency contacts
        UUID driverId = incident.getDriverId();
        if (driverId != null) {
            List<EmergencyContactDto> contacts = authServiceClient.getEmergencyContacts(driverId);
            if (contacts != null && !contacts.isEmpty()) {
                notificationService.sendFalseAlarmSms(contacts, incident.getDriverName());
            }
        }

        gpsStreamingService.cleanupIncident(incident.getId().toString());
        log.info("Incident {} cancelled by user", incident.getId());
    }

    // ═══════════════════════════════════════════════════
    //  RESPONDER ACTIONS
    // ═══════════════════════════════════════════════════

    @Transactional
    public DispatchAckResponse acceptDispatch(UUID incidentId, UUID responderId) {
        Incident incident = findIncident(incidentId);
        requireOfferedTo(incident, responderId);

        int won = incidentRepository.acceptOffer(incidentId, responderId,
                IncidentStatus.DISPATCHED, IncidentStatus.ACCEPTED, Instant.now());
        if (won == 0) {
            throw new InvalidIncidentStateException(
                    "The offer is no longer open (it timed out, was cancelled or was already answered)");
        }

        escalationTimerService.cancelTimer(incidentId);
        markAttemptResponse(incidentId, responderId, DispatchResponseType.ACCEPTED);

        // Update responder status to ON_MISSION
        spatialQueryService.markResponderOnMission(responderId);
        updateAuthStatus(responderId, "ON_MISSION");

        Map<String, Object> responder = data(
                "name", incident.getResponderName(),
                "phone", incident.getResponderPhone(),
                "vehicleId", incident.getResponderVehicleId());
        emit(IncidentEventMessage.INCIDENT_ACCEPTED, incident, responderId,
                data("incidentId", incidentId.toString(), "responder", responder));

        log.info("Dispatch ACCEPTED: incident={}, responder={}", incidentId, responderId);

        return ack(incidentId, "ACCEPTED", "Dispatch accepted. GPS streaming enabled.");
    }

    @Transactional
    public DispatchAckResponse rejectDispatch(UUID incidentId, UUID responderId) {
        Incident incident = findIncident(incidentId);
        requireOfferedTo(incident, responderId);

        if (!releaseOffer(incident, responderId, DispatchResponseType.REJECTED)) {
            throw new InvalidIncidentStateException(
                    "The offer is no longer open (it timed out, was cancelled or was already answered)");
        }

        log.info("Dispatch REJECTED by responder={} for incident={}", responderId, incidentId);

        // Immediately escalate to next responder
        dispatchToNearest(incidentId);

        return ack(incidentId, "REJECTED", "Dispatch rejected. Escalating to next responder.");
    }

    /**
     * PHASE 6: "Accept again" from the responder's history.
     * A responder who rejected (or missed) an offer may still take the emergency, as long as nobody
     * has accepted it yet. If another responder is being asked right now, that offer is taken back
     * (they get INCIDENT_OFFER_EXPIRED). Then this responder is assigned and it is ACCEPTED at once.
     *   403 never offered to you · 409 already taken, finished, or you are busy with another one.
     */
    @Transactional
    public DispatchAckResponse reacceptDispatch(UUID incidentId, UUID responderId) {
        Incident incident = findIncident(incidentId);

        // Still their own open offer: a normal accept.
        if (responderId.equals(incident.getAssignedResponderId())
                && incident.getStatus() == IncidentStatus.DISPATCHED) {
            return acceptDispatch(incidentId, responderId);
        }
        if (!attemptRepository.existsByIncidentIdAndResponderId(incidentId, responderId)) {
            throw new IncidentAccessDeniedException("This emergency was never offered to you");
        }
        if (incidentRepository.findFirstByAssignedResponderIdAndStatusInOrderByCreatedAtDesc(
                responderId, IncidentService.ACCEPTED_AND_ON).isPresent()) {
            throw new InvalidIncidentStateException("Finish your current emergency first");
        }

        // Somebody else is being asked right now: take the offer back from them.
        if (incident.getStatus() == IncidentStatus.DISPATCHED && incident.getAssignedResponderId() != null) {
            releaseOffer(incident, incident.getAssignedResponderId(), DispatchResponseType.TIMED_OUT);
            incident = findIncident(incidentId);
        }
        if (incident.getStatus() != IncidentStatus.DISPATCHING) {
            throw new InvalidIncidentStateException(
                    "This emergency is " + incident.getStatus() + " now. It cannot be taken again.");
        }

        ResponderLocation me = spatialQueryService.findLocation(responderId);
        if (me == null) {
            throw new InvalidIncidentStateException("Go on duty once (so Dispatch knows your vehicle), then try again");
        }

        Instant now = Instant.now();
        int claimed = incidentRepository.claimOffer(incidentId,
                IncidentStatus.DISPATCHING, IncidentStatus.DISPATCHED,
                responderId, me.getUserName(), me.getPhone(), me.getVehicleId(),
                now.plusSeconds(offerSeconds));
        if (claimed == 0) {
            throw new InvalidIncidentStateException("This emergency changed just now. Open it again.");
        }
        attemptRepository.save(DispatchAttempt.builder()
                .incidentId(incidentId)
                .responderId(responderId)
                .attemptNumber(attemptRepository.countByIncidentId(incidentId) + 1)
                .sentAt(now)
                .response(DispatchResponseType.PENDING)
                .build());
        log.info("Responder {} takes incident {} again from their history", responderId, incidentId);

        return acceptDispatch(incidentId, responderId);
    }

    @Transactional
    public DispatchAckResponse updateIncidentStatus(UUID incidentId, UUID responderId,
                                                    IncidentStatus newStatus, String hospitalId) {
        Incident incident = findIncident(incidentId);

        if (!responderId.equals(incident.getAssignedResponderId())) {
            throw new IncidentAccessDeniedException("You are not the responder assigned to this incident");
        }

        Instant now = Instant.now();
        int won;
        switch (newStatus) {
            case ARRIVED -> won = incidentRepository.markArrived(incidentId, responderId,
                    IncidentStatus.ACCEPTED, IncidentStatus.ARRIVED, now);
            case EN_ROUTE_HOSPITAL -> won = incidentRepository.markEnRoute(incidentId, responderId,
                    IncidentStatus.ARRIVED, IncidentStatus.EN_ROUTE_HOSPITAL, now, hospitalId);
            case RESOLVED -> won = incidentRepository.markResolved(incidentId, responderId,
                    List.of(IncidentStatus.ARRIVED, IncidentStatus.EN_ROUTE_HOSPITAL),
                    IncidentStatus.RESOLVED, now);
            default -> throw new IllegalArgumentException("Status " + newStatus
                    + " cannot be set here. Use ARRIVED, EN_ROUTE_HOSPITAL or RESOLVED.");
        }

        if (won == 0) {
            throw new InvalidIncidentStateException(
                    "Cannot move this incident from " + incident.getStatus() + " to " + newStatus);
        }

        if (newStatus == IncidentStatus.EN_ROUTE_HOSPITAL) {
            notificationService.sendHospitalPreAlert(
                    hospitalId, incidentId,
                    incident.getBloodType(), incident.getAllergies(), "Calculating...");
        } else if (newStatus == IncidentStatus.RESOLVED) {
            gpsStreamingService.cleanupIncident(incidentId.toString());
            spatialQueryService.markResponderOnDuty(responderId);
            updateAuthStatus(responderId, "ON_DUTY");
        }

        emit(IncidentEventMessage.INCIDENT_STATUS, incident, responderId,
                data("incidentId", incidentId.toString(), "status", newStatus.name()));

        log.info("Incident {} status → {}", incidentId, newStatus);

        return ack(incidentId, newStatus.name(), "Incident status updated.");
    }

    @Transactional
    public DispatchAckResponse markBystanderAssisting(UUID incidentId, Viewer viewer) {
        Incident incident = findIncident(incidentId);

        if (!viewer.canSee(incident)) {
            throw new IncidentAccessDeniedException("This incident does not belong to you");
        }
        if (!IncidentService.ACTIVE.contains(incident.getStatus())) {
            throw new InvalidIncidentStateException("Incident is already " + incident.getStatus());
        }

        // Only the first tap tells the responder; later taps are accepted quietly.
        int first = incidentRepository.markBystanderAssisting(incidentId, IncidentService.ACTIVE);
        if (first == 1) {
            emit(IncidentEventMessage.BYSTANDER_ASSISTING, incident, incident.getAssignedResponderId(),
                    data("incidentId", incidentId.toString()));
        }
        log.info("Bystander assisting at incident={}", incidentId);

        return ack(incidentId, "BYSTANDER_NOTIFIED", "Dispatchers notified that a bystander is assisting.");
    }

    // ═══════════════════════════════════════════════════
    //  DISPATCH ENGINE
    // ═══════════════════════════════════════════════════

    /**
     * Find the best responder and give them a 30-second offer.
     * Safe to call from any thread: if the incident is no longer DISPATCHING it does nothing,
     * and the final "claim" is a conditional update that only one caller can win.
     */
    void dispatchToNearest(UUID incidentId) {
        Incident incident = incidentRepository.findById(incidentId).orElse(null);
        if (incident == null || incident.getStatus() != IncidentStatus.DISPATCHING) {
            log.info("Incident {} is not waiting for a responder — nothing to dispatch", incidentId);
            return;
        }

        Instant now = Instant.now();

        // Too old: stop ringing people about a crash nobody is answering.
        if (incident.getCreatedAt() != null
                && now.isAfter(incident.getCreatedAt().plus(giveUpMinutes, ChronoUnit.MINUTES))) {
            incidentRepository.stopRetrying(incidentId, IncidentStatus.DISPATCHING);
            log.warn("Incident {} is older than {} minutes with no accepted responder — giving up",
                    incidentId, giveUpMinutes);
            return;
        }

        announceNoResponderIfDue(incident, now);

        ResponderLocation nearest = pickResponder(incident);
        if (nearest == null) {
            incidentRepository.scheduleRetry(incidentId, IncidentStatus.DISPATCHING,
                    now.plusSeconds(retryIntervalSeconds));
            log.warn("No responder free for incident={} — will try again in {} seconds",
                    incidentId, retryIntervalSeconds);
            return;
        }

        Instant expiresAt = now.plusSeconds(offerSeconds);
        int claimed = incidentRepository.claimOffer(incidentId,
                IncidentStatus.DISPATCHING, IncidentStatus.DISPATCHED,
                nearest.getResponderId(), nearest.getUserName(), nearest.getPhone(),
                nearest.getVehicleId(), expiresAt);
        if (claimed == 0) {
            log.info("Incident {} changed while choosing a responder (cancelled or already offered)", incidentId);
            return;
        }

        // Create dispatch attempt
        int attemptNum = attemptRepository.countByIncidentId(incidentId) + 1;
        attemptRepository.save(DispatchAttempt.builder()
                .incidentId(incidentId)
                .responderId(nearest.getResponderId())
                .attemptNumber(attemptNum)
                .sentAt(now)
                .response(DispatchResponseType.PENDING)
                .build());

        // Send push notification
        notificationService.sendResponderPush(
                nearest.getResponderId(), nearest.getVehicleId(),
                incidentId, incident.getDriverName(),
                incident.getGpsLat(), incident.getGpsLng(),
                incident.getPriorityScore() != null ? incident.getPriorityScore() : 0);

        // Start the live 30-second timer (the sweep covers a restart)
        final UUID responderId = nearest.getResponderId();
        escalationTimerService.startEscalationTimer(incidentId, offerSeconds,
                () -> onOfferTimeout(incidentId, responderId));

        emit(IncidentEventMessage.INCIDENT_OFFERED, incident, responderId,
                data("incidentId", incidentId.toString(),
                        "dispatchExpiresAt", expiresAt.toString(),
                        "driverName", incident.getDriverName(),
                        "gpsLat", incident.getGpsLat(),
                        "gpsLng", incident.getGpsLng(),
                        "priorityScore", incident.getPriorityScore(),
                        "bloodType", incident.getBloodType(),
                        "allergies", incident.getAllergies(),
                        "attempt", attemptNum));

        log.info("Dispatched to responder {} (attempt #{}), incident={}", responderId, attemptNum, incidentId);
    }

    /**
     * The offer's time is over. Called by the live timer and by the sweep.
     * If the responder already answered (or the driver cancelled), the conditional update loses
     * and nothing happens.
     */
    public void onOfferTimeout(UUID incidentId, UUID responderId) {
        Incident incident = incidentRepository.findById(incidentId).orElse(null);
        if (incident == null) {
            return;
        }
        if (releaseOffer(incident, responderId, DispatchResponseType.TIMED_OUT)) {
            log.info("Offer to responder={} timed out for incident={} — escalating", responderId, incidentId);
            dispatchToNearest(incidentId);
        }
    }

    /** Called by the sweep when an incident is waiting for another try. */
    public void retryDispatch(UUID incidentId) {
        dispatchToNearest(incidentId);
    }

    // ═══════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════

    private Incident findIncident(UUID incidentId) {
        return incidentRepository.findById(incidentId)
                .orElseThrow(() -> new IncidentNotFoundException("Incident not found: " + incidentId));
    }

    /**
     * 403 if this incident was never offered to the caller.
     * 409 if it WAS offered to them but the offer is over, or the incident has moved on.
     */
    private void requireOfferedTo(Incident incident, UUID responderId) {
        if (!responderId.equals(incident.getAssignedResponderId())) {
            if (attemptRepository.existsByIncidentIdAndResponderId(incident.getId(), responderId)) {
                throw new InvalidIncidentStateException(
                        "Your offer for this incident is over (incident is " + incident.getStatus() + ")");
            }
            throw new IncidentAccessDeniedException("This incident was not offered to you");
        }
        if (incident.getStatus() != IncidentStatus.DISPATCHED) {
            throw new InvalidIncidentStateException(
                    "Incident is " + incident.getStatus() + ", it is not waiting for an answer");
        }
    }

    /**
     * DISPATCHED -> DISPATCHING for this responder (reject or time-out).
     * Returns false if somebody else got there first.
     */
    private boolean releaseOffer(Incident incident, UUID responderId, DispatchResponseType reason) {
        UUID incidentId = incident.getId();
        int won = incidentRepository.releaseOffer(incidentId, responderId,
                IncidentStatus.DISPATCHED, IncidentStatus.DISPATCHING,
                Instant.now().plusSeconds(safetyRetrySeconds));
        if (won == 0) {
            return false;
        }
        escalationTimerService.cancelTimer(incidentId);
        markAttemptResponse(incidentId, responderId, reason);
        emit(IncidentEventMessage.INCIDENT_OFFER_EXPIRED, incident, responderId,
                data("incidentId", incidentId.toString()));
        return true;
    }

    /**
     * Nearest responder nobody has asked yet. If everybody was asked, nearest responder who did not
     * REJECT (someone who only let it time out may be free now). Null if there is nobody.
     */
    private ResponderLocation pickResponder(Incident incident) {
        if (incident.getGpsLat() == null || incident.getGpsLng() == null) {
            log.error("Incident {} has no GPS position — cannot choose a responder", incident.getId());
            return null;
        }

        List<DispatchAttempt> attempts = attemptRepository.findByIncidentIdOrderByAttemptNumber(incident.getId());
        Set<UUID> asked = new LinkedHashSet<>();
        Set<UUID> rejected = new LinkedHashSet<>();
        for (DispatchAttempt a : attempts) {
            asked.add(a.getResponderId());
            if (a.getResponse() == DispatchResponseType.REJECTED) {
                rejected.add(a.getResponderId());
            }
        }

        ResponderLocation nearest = spatialQueryService.findNearestResponder(
                incident.getGpsLat(), incident.getGpsLng(), new ArrayList<>(asked));
        if (nearest == null && rejected.size() < asked.size()) {
            nearest = spatialQueryService.findNearestResponder(
                    incident.getGpsLat(), incident.getGpsLng(), new ArrayList<>(rejected));
        }
        return nearest;
    }

    /** 2 minutes without an accept: tell the driver once. Dispatch keeps trying. */
    private void announceNoResponderIfDue(Incident incident, Instant now) {
        if (incident.getCreatedAt() == null
                || now.isBefore(incident.getCreatedAt().plusSeconds(noResponderAfterSeconds))) {
            return;
        }
        if (incidentRepository.markNoResponderNotified(incident.getId(), IncidentStatus.DISPATCHING) == 1) {
            log.warn("Nobody accepted incident {} for {} seconds", incident.getId(), noResponderAfterSeconds);
            emit(IncidentEventMessage.NO_RESPONDER_AVAILABLE, incident, null,
                    data("incidentId", incident.getId().toString()));
        }
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

    private void updateAuthStatus(UUID userId, String status) {
        try {
            authServiceClient.updateResponderStatus(userId, status);
        } catch (Exception e) {
            log.warn("Could not set responder {} to {} in Auth: {}", userId, status, e.getMessage());
        }
    }

    private void emit(String type, Incident incident, UUID responderId, Map<String, Object> data) {
        eventPublisher.publish(type, incident, responderId, data);
    }

    /** Key/value pairs into a map that allows null values (Map.of does not). */
    private static Map<String, Object> data(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static DispatchAckResponse ack(UUID incidentId, String status, String message) {
        return DispatchAckResponse.builder()
                .incidentId(incidentId.toString())
                .status(status)
                .message(message)
                .build();
    }

    private MedicalProfileDto fallbackProfile() {
        return MedicalProfileDto.builder()
                .bloodType("UNKNOWN").allergies("UNKNOWN").medications("UNKNOWN")
                .chronicConditions("UNKNOWN").emergencyNotes("Profile unavailable").build();
    }
}
