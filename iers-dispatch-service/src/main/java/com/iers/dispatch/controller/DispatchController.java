package com.iers.dispatch.controller;

import com.iers.dispatch.dto.Viewer;
import com.iers.dispatch.dto.request.UpdateIncidentStatusRequest;
import com.iers.dispatch.dto.response.DispatchAckResponse;
import com.iers.dispatch.dto.response.IncidentResponse;
import com.iers.dispatch.service.DispatchOrchestrator;
import com.iers.dispatch.service.IncidentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Responder actions. The gateway sets X-User-Id (and X-User-Role) from the JWT.
 *   400 bad ID or missing header, 403 wrong person, 404 unknown incident, 409 wrong state.
 */
@RestController
@RequestMapping("/api/dispatch")
@RequiredArgsConstructor
public class DispatchController {

    private final DispatchOrchestrator orchestrator;
    private final IncidentService incidentService;

    /** The offer waiting for this responder, or 204. */
    @GetMapping("/offers/current")
    public ResponseEntity<IncidentResponse> currentOffer(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader(value = "X-User-Role", defaultValue = "UNKNOWN") String role) {
        return incidentService.getCurrentOffer(new Viewer(userId, role))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Responder taps "ACCEPT DISPATCH" (only the offered responder, only while DISPATCHED)
     */
    @PostMapping("/{incidentId}/accept")
    public ResponseEntity<DispatchAckResponse> accept(
            @PathVariable UUID incidentId,
            @RequestHeader("X-User-Id") UUID responderId) {
        return ResponseEntity.ok(orchestrator.acceptDispatch(incidentId, responderId));
    }

    /**
     * Responder taps "REJECT" (same rule as accept)
     */
    @PostMapping("/{incidentId}/reject")
    public ResponseEntity<DispatchAckResponse> reject(
            @PathVariable UUID incidentId,
            @RequestHeader("X-User-Id") UUID responderId) {
        return ResponseEntity.ok(orchestrator.rejectDispatch(incidentId, responderId));
    }

    /**
     * Status transitions: ARRIVED, EN_ROUTE_HOSPITAL, RESOLVED (only the assigned responder)
     */
    @PostMapping("/{incidentId}/status")
    public ResponseEntity<DispatchAckResponse> updateStatus(
            @PathVariable UUID incidentId,
            @RequestHeader("X-User-Id") UUID responderId,
            @Valid @RequestBody UpdateIncidentStatusRequest request) {
        return ResponseEntity.ok(orchestrator.updateIncidentStatus(
                incidentId, responderId, request.getStatus(), request.getHospitalId()));
    }
}
