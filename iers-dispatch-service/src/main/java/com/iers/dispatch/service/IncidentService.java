package com.iers.dispatch.service;

import com.iers.dispatch.dto.Viewer;
import com.iers.dispatch.dto.response.IncidentResponse;
import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.entity.enums.IncidentStatus;
import com.iers.dispatch.exception.IncidentAccessDeniedException;
import com.iers.dispatch.exception.IncidentNotFoundException;
import com.iers.dispatch.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only lookups. Every answer is shaped for the person asking (see IncidentResponse),
 * and nobody can open an incident that is not theirs.
 */
@Service
@RequiredArgsConstructor
public class IncidentService {

    /** Not finished yet. */
    public static final List<IncidentStatus> ACTIVE = List.of(
            IncidentStatus.DISPATCHING,
            IncidentStatus.DISPATCHED,
            IncidentStatus.ACCEPTED,
            IncidentStatus.ARRIVED,
            IncidentStatus.EN_ROUTE_HOSPITAL);

    /** A responder "has" the incident only after accepting. Before that it is just an offer. */
    public static final List<IncidentStatus> ACCEPTED_AND_ON = List.of(
            IncidentStatus.ACCEPTED,
            IncidentStatus.ARRIVED,
            IncidentStatus.EN_ROUTE_HOSPITAL);

    private final IncidentRepository incidentRepository;

    /** GET /api/incidents/{id} — its driver, its responder or an admin. Others get 403. */
    public IncidentResponse getById(UUID incidentId, Viewer viewer) {
        Incident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new IncidentNotFoundException("Incident not found: " + incidentId));
        if (!viewer.canSee(incident)) {
            throw new IncidentAccessDeniedException("This incident does not belong to you");
        }
        return IncidentResponse.from(incident, viewer, Instant.now());
    }

    /** GET /api/incidents/active — driver: own; responder: theirs; admin: all. */
    public List<IncidentResponse> getActiveIncidents(Viewer viewer) {
        Instant now = Instant.now();
        List<Incident> incidents = viewer.isAdmin()
                ? incidentRepository.findByStatusIn(ACTIVE)
                : incidentRepository.findForUser(ACTIVE, viewer.id());
        return incidents.stream().map(i -> IncidentResponse.from(i, viewer, now)).toList();
    }

    /** GET /api/incidents/mine — my current unfinished incident (empty = 204). */
    public Optional<IncidentResponse> getMine(Viewer viewer) {
        Instant now = Instant.now();
        Optional<Incident> found;
        if (viewer.isDriver()) {
            found = incidentRepository.findFirstByDriverIdAndStatusInOrderByCreatedAtDesc(viewer.id(), ACTIVE);
        } else if (viewer.isResponder()) {
            found = incidentRepository.findFirstByAssignedResponderIdAndStatusInOrderByCreatedAtDesc(
                    viewer.id(), ACCEPTED_AND_ON);
        } else {
            throw new IncidentAccessDeniedException("Only drivers and responders have a personal incident");
        }
        return found.map(i -> IncidentResponse.from(i, viewer, now));
    }

    /** GET /api/incidents/by-crash/{crashEventId} — the driver who crashed (or an admin). 404 until created. */
    public IncidentResponse getByCrash(UUID crashEventId, Viewer viewer) {
        Incident incident = incidentRepository.findByCrashEventId(crashEventId)
                .orElseThrow(() -> new IncidentNotFoundException(
                        "No incident for crash " + crashEventId + " yet"));
        if (!viewer.isAdmin() && !viewer.isDriverOf(incident)) {
            throw new IncidentAccessDeniedException("This crash does not belong to you");
        }
        return IncidentResponse.from(incident, viewer, Instant.now());
    }

    /** GET /api/dispatch/offers/current — the open offer waiting for this responder (empty = 204). */
    public Optional<IncidentResponse> getCurrentOffer(Viewer viewer) {
        Instant now = Instant.now();
        return incidentRepository
                .findFirstByAssignedResponderIdAndStatusInOrderByCreatedAtDesc(
                        viewer.id(), List.of(IncidentStatus.DISPATCHED))
                .map(i -> IncidentResponse.from(i, viewer, now));
    }
}
