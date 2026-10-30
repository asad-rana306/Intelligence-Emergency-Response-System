package com.iers.dispatch.controller;

import com.iers.dispatch.dto.Viewer;
import com.iers.dispatch.dto.response.DispatchAckResponse;
import com.iers.dispatch.dto.response.IncidentResponse;
import com.iers.dispatch.service.DispatchOrchestrator;
import com.iers.dispatch.service.IncidentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Incident lookups. Nobody sees an incident that is not theirs (admins see all).
 * The gateway sets X-User-Id and X-User-Role from the JWT.
 */
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService incidentService;
    private final DispatchOrchestrator orchestrator;

    @GetMapping("/{incidentId}")
    public ResponseEntity<IncidentResponse> getById(
            @PathVariable UUID incidentId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader(value = "X-User-Role", defaultValue = "UNKNOWN") String role) {
        return ResponseEntity.ok(incidentService.getById(incidentId, new Viewer(userId, role)));
    }

    /** Driver: own incidents. Responder: theirs. Admin: all. */
    @GetMapping("/active")
    public ResponseEntity<List<IncidentResponse>> getActive(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader(value = "X-User-Role", defaultValue = "UNKNOWN") String role) {
        return ResponseEntity.ok(incidentService.getActiveIncidents(new Viewer(userId, role)));
    }

    /** "What is my incident?" — current unfinished incident, or 204. */
    @GetMapping("/mine")
    public ResponseEntity<IncidentResponse> getMine(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader(value = "X-User-Role", defaultValue = "UNKNOWN") String role) {
        return incidentService.getMine(new Viewer(userId, role))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** The driver asks for the incident made from their crash. 404 until Dispatch has created it. */
    @GetMapping("/by-crash/{crashEventId}")
    public ResponseEntity<IncidentResponse> getByCrash(
            @PathVariable UUID crashEventId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader(value = "X-User-Role", defaultValue = "UNKNOWN") String role) {
        return ResponseEntity.ok(incidentService.getByCrash(crashEventId, new Viewer(userId, role)));
    }

    @PostMapping("/{incidentId}/bystander-assist")
    public ResponseEntity<DispatchAckResponse> bystanderAssist(
            @PathVariable UUID incidentId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader(value = "X-User-Role", defaultValue = "UNKNOWN") String role) {
        return ResponseEntity.ok(orchestrator.markBystanderAssisting(incidentId, new Viewer(userId, role)));
    }
}
