package com.iers.dispatch.controller;

import com.iers.dispatch.dto.response.DispatchAckResponse;
import com.iers.dispatch.dto.response.IncidentResponse;
import com.iers.dispatch.service.DispatchOrchestrator;
import com.iers.dispatch.service.IncidentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService incidentService;
    private final DispatchOrchestrator orchestrator;

    @GetMapping("/{incidentId}")
    public ResponseEntity<IncidentResponse> getById(@PathVariable UUID incidentId) {
        return ResponseEntity.ok(incidentService.getById(incidentId));
    }

    @GetMapping("/active")
    public ResponseEntity<List<IncidentResponse>> getActive() {
        return ResponseEntity.ok(incidentService.getActiveIncidents());
    }

    @PostMapping("/{incidentId}/bystander-assist")
    public ResponseEntity<DispatchAckResponse> bystanderAssist(@PathVariable UUID incidentId) {
        orchestrator.markBystanderAssisting(incidentId);
        return ResponseEntity.ok(DispatchAckResponse.builder()
                .incidentId(incidentId.toString())
                .status("BYSTANDER_NOTIFIED")
                .message("Dispatchers notified that a bystander is assisting.")
                .build());
    }
}
