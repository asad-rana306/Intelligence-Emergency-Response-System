package com.iers.dispatch.service;

import com.iers.dispatch.dto.Viewer;
import com.iers.dispatch.dto.response.IncidentResponse;
import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.entity.enums.IncidentStatus;
import com.iers.dispatch.exception.IncidentAccessDeniedException;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    @Mock private IncidentRepository incidentRepository;
    @InjectMocks private IncidentService service;

    private final UUID incidentId = UUID.randomUUID();
    private final UUID crashId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final UUID responderId = UUID.randomUUID();

    private Incident incident(IncidentStatus status) {
        return Incident.builder()
                .id(incidentId).crashEventId(crashId).driverId(driverId)
                .driverName("John").driverPhone("+1415").status(status)
                .assignedResponderId(responderId).priorityScore(4)
                .bloodType("O+").allergies("None").build();
    }

    // ── getById ──

    @Test
    @DisplayName("getById — the driver can open their incident")
    void getById_driver() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident(IncidentStatus.DISPATCHED)));

        IncidentResponse response = service.getById(incidentId, new Viewer(driverId, "DRIVER"));

        assertThat(response.getDriverName()).isEqualTo("John");
        assertThat(response.getStatus()).isEqualTo(IncidentStatus.DISPATCHED);
        assertThat(response.getServerTime()).isNotNull();
    }

    @Test
    @DisplayName("getById — the assigned responder and an admin can open it too")
    void getById_responderAndAdmin() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident(IncidentStatus.ACCEPTED)));

        assertThat(service.getById(incidentId, new Viewer(responderId, "RESPONDER")).getId())
                .isEqualTo(incidentId.toString());
        assertThat(service.getById(incidentId, new Viewer(UUID.randomUUID(), "ADMIN")).getId())
                .isEqualTo(incidentId.toString());
    }

    @Test
    @DisplayName("getById — someone else gets 403")
    void getById_stranger() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident(IncidentStatus.ACCEPTED)));
        assertThatThrownBy(() -> service.getById(incidentId, new Viewer(UUID.randomUUID(), "DRIVER")))
                .isInstanceOf(IncidentAccessDeniedException.class);
    }

    @Test
    @DisplayName("getById — unknown incident gives 404")
    void getById_notFound() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(incidentId, new Viewer(driverId, "DRIVER")))
                .isInstanceOf(IncidentNotFoundException.class);
    }

    // ── active ──

    @Test
    @DisplayName("getActiveIncidents — admin sees all active incidents")
    void active_admin() {
        when(incidentRepository.findByStatusIn(anyCollection()))
                .thenReturn(List.of(incident(IncidentStatus.DISPATCHED), incident(IncidentStatus.ACCEPTED)));

        List<IncidentResponse> result = service.getActiveIncidents(new Viewer(UUID.randomUUID(), "ADMIN"));

        assertThat(result).hasSize(2);
        verify(incidentRepository, never()).findForUser(any(), any());
    }

    @Test
    @DisplayName("getActiveIncidents — a driver or responder sees only their own")
    void active_user() {
        when(incidentRepository.findForUser(anyCollection(), eq(driverId)))
                .thenReturn(List.of(incident(IncidentStatus.DISPATCHED)));

        List<IncidentResponse> result = service.getActiveIncidents(new Viewer(driverId, "DRIVER"));

        assertThat(result).hasSize(1);
        verify(incidentRepository, never()).findByStatusIn(any());
    }

    // ── mine ──

    @Test
    @DisplayName("getMine — driver gets their unfinished incident, including one still waiting for a responder")
    void mine_driver() {
        when(incidentRepository.findFirstByDriverIdAndStatusInOrderByCreatedAtDesc(eq(driverId), anyCollection()))
                .thenReturn(Optional.of(incident(IncidentStatus.DISPATCHING)));

        assertThat(service.getMine(new Viewer(driverId, "DRIVER"))).isPresent();
    }

    @Test
    @DisplayName("getMine — driver with no incident gets empty (204)")
    void mine_driverNone() {
        when(incidentRepository.findFirstByDriverIdAndStatusInOrderByCreatedAtDesc(eq(driverId), anyCollection()))
                .thenReturn(Optional.empty());

        assertThat(service.getMine(new Viewer(driverId, "DRIVER"))).isEmpty();
    }

    @Test
    @DisplayName("getMine — responder only has an incident after accepting (an offer is not one)")
    void mine_responder() {
        when(incidentRepository.findFirstByAssignedResponderIdAndStatusInOrderByCreatedAtDesc(
                eq(responderId), eq(IncidentService.ACCEPTED_AND_ON)))
                .thenReturn(Optional.of(incident(IncidentStatus.ACCEPTED)));

        assertThat(service.getMine(new Viewer(responderId, "RESPONDER"))).isPresent();
    }

    @Test
    @DisplayName("getMine — an admin has no personal incident (403)")
    void mine_admin() {
        assertThatThrownBy(() -> service.getMine(new Viewer(UUID.randomUUID(), "ADMIN")))
                .isInstanceOf(IncidentAccessDeniedException.class);
    }

    // ── by crash ──

    @Test
    @DisplayName("getByCrash — the driver who crashed finds their incident")
    void byCrash_owner() {
        when(incidentRepository.findByCrashEventId(crashId)).thenReturn(Optional.of(incident(IncidentStatus.DISPATCHING)));

        IncidentResponse response = service.getByCrash(crashId, new Viewer(driverId, "DRIVER"));

        assertThat(response.getCrashEventId()).isEqualTo(crashId.toString());
    }

    @Test
    @DisplayName("getByCrash — another driver gets 403")
    void byCrash_stranger() {
        when(incidentRepository.findByCrashEventId(crashId)).thenReturn(Optional.of(incident(IncidentStatus.DISPATCHING)));
        assertThatThrownBy(() -> service.getByCrash(crashId, new Viewer(UUID.randomUUID(), "DRIVER")))
                .isInstanceOf(IncidentAccessDeniedException.class);
    }

    @Test
    @DisplayName("getByCrash — an admin may look")
    void byCrash_admin() {
        when(incidentRepository.findByCrashEventId(crashId)).thenReturn(Optional.of(incident(IncidentStatus.DISPATCHING)));
        assertThat(service.getByCrash(crashId, new Viewer(UUID.randomUUID(), "ADMIN"))).isNotNull();
    }

    @Test
    @DisplayName("getByCrash — 404 until Dispatch has created the incident")
    void byCrash_notYet() {
        when(incidentRepository.findByCrashEventId(crashId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getByCrash(crashId, new Viewer(driverId, "DRIVER")))
                .isInstanceOf(IncidentNotFoundException.class);
    }

    // ── current offer ──

    @Test
    @DisplayName("getCurrentOffer — returns the open offer with its countdown")
    void offer_present() {
        Incident inc = incident(IncidentStatus.DISPATCHED);
        inc.setDispatchExpiresAt(java.time.Instant.now().plusSeconds(20));
        when(incidentRepository.findFirstByAssignedResponderIdAndStatusInOrderByCreatedAtDesc(
                eq(responderId), eq(List.of(IncidentStatus.DISPATCHED)))).thenReturn(Optional.of(inc));

        Optional<IncidentResponse> offer = service.getCurrentOffer(new Viewer(responderId, "RESPONDER"));

        assertThat(offer).isPresent();
        assertThat(offer.get().getDispatchExpiresAt()).isNotNull();
        assertThat(offer.get().getBloodType()).isEqualTo("O+"); // the offered responder sees medical info
    }

    @Test
    @DisplayName("getCurrentOffer — nothing waiting gives empty (204)")
    void offer_none() {
        when(incidentRepository.findFirstByAssignedResponderIdAndStatusInOrderByCreatedAtDesc(
                eq(responderId), anyCollection())).thenReturn(Optional.empty());
        assertThat(service.getCurrentOffer(new Viewer(responderId, "RESPONDER"))).isEmpty();
    }
}
