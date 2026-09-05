package com.iers.dispatch.service;

import com.iers.dispatch.dto.response.IncidentResponse;
import com.iers.dispatch.entity.enums.IncidentStatus;
import com.iers.dispatch.exception.IncidentNotFoundException;
import com.iers.dispatch.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IncidentService {

    private final IncidentRepository incidentRepository;

    public IncidentResponse getById(UUID incidentId) {
        return incidentRepository.findById(incidentId)
                .map(IncidentResponse::from)
                .orElseThrow(() -> new IncidentNotFoundException("Incident not found: " + incidentId));
    }

    public List<IncidentResponse> getActiveIncidents() {
        return incidentRepository.findByStatusIn(List.of(
                        IncidentStatus.DISPATCHING,
                        IncidentStatus.DISPATCHED,
                        IncidentStatus.ACCEPTED,
                        IncidentStatus.ARRIVED,
                        IncidentStatus.EN_ROUTE_HOSPITAL))
                .stream()
                .map(IncidentResponse::from)
                .toList();
    }
}
