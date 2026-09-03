package com.iers.dispatch.controller;

import com.iers.dispatch.dto.request.UpdateIncidentStatusRequest;
import com.iers.dispatch.dto.response.DispatchAckResponse;
import com.iers.dispatch.service.DispatchOrchestrator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/dispatch")
@RequiredArgsConstructor
public class DispatchController {

    private final DispatchOrchestrator orchestrator;

    /**
     * Responder taps "ACCEPT DISPATCH"
     */
    @PostMapping("/{incidentId}/accept")
    public ResponseEntity<DispatchAckResponse> accept(
            @PathVariable UUID incidentId,
            @RequestHeader("X-User-Id") String responderId) {
        return ResponseEntity.ok(orchestrator.acceptDispatch(incidentId, responderId));
    }

    /**
     * Responder taps "REJECT"
     */
    @PostMapping("/{incidentId}/reject")
    public ResponseEntity<DispatchAckResponse> reject(
            @PathVariable UUID incidentId,
            @RequestHeader("X-User-Id") String responderId) {
        return ResponseEntity.ok(orchestrator.rejectDispatch(incidentId, responderId));
    }

    /**
     * Status transitions: ARRIVED, EN_ROUTE_HOSPITAL, RESOLVED
     */
    @PostMapping("/{incidentId}/status")
    public ResponseEntity<DispatchAckResponse> updateStatus(
            @PathVariable UUID incidentId,
            @Valid @RequestBody UpdateIncidentStatusRequest request) {
        return ResponseEntity.ok(orchestrator.updateIncidentStatus(
                incidentId, request.getStatus(), request.getHospitalId()));
    }
}
