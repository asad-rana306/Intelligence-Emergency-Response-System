package com.iers.dispatch.service;

import com.iers.dispatch.dto.response.IncidentResponse;
import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.entity.enums.IncidentStatus;
import com.iers.dispatch.exception.IncidentNotFoundException;
import com.iers.dispatch.repository.IncidentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    @Mock private IncidentRepository incidentRepository;
    @InjectMocks private IncidentService service;

    private final UUID incidentId = UUID.randomUUID();

    @Test
    @DisplayName("getById — returns incident")
    void getById_success() {
        Incident incident = Incident.builder()
                .id(incidentId).crashEventId(UUID.randomUUID())
                .driverName("John").status(IncidentStatus.DISPATCHED)
                .priorityScore(4).build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));

        IncidentResponse response = service.getById(incidentId);
        assertThat(response.getDriverName()).isEqualTo("John");
        assertThat(response.getStatus()).isEqualTo(IncidentStatus.DISPATCHED);
    }

    @Test
    @DisplayName("getById — throws when not found")
    void getById_notFound() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(incidentId))
                .isInstanceOf(IncidentNotFoundException.class);
    }

    @Test
    @DisplayName("getActiveIncidents — returns only active statuses")
    void getActive() {
        List<Incident> active = List.of(
                Incident.builder().id(UUID.randomUUID()).crashEventId(UUID.randomUUID())
                        .driverName("A").status(IncidentStatus.DISPATCHED).build(),
                Incident.builder().id(UUID.randomUUID()).crashEventId(UUID.randomUUID())
                        .driverName("B").status(IncidentStatus.ACCEPTED).build()
        );
        when(incidentRepository.findByStatusIn(anyList())).thenReturn(active);

        List<IncidentResponse> result = service.getActiveIncidents();
        assertThat(result).hasSize(2);
    }
}
