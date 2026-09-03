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
import com.iers.dispatch.feign.AuthServiceClient;
import com.iers.dispatch.repository.DispatchAttemptRepository;
import com.iers.dispatch.repository.IncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DispatchOrchestratorTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private DispatchAttemptRepository attemptRepository;
    @Mock private AuthServiceClient authServiceClient;
    @Mock private SpatialQueryService spatialQueryService;
    @Mock private EscalationTimerService escalationTimerService;
    @Mock private NotificationService notificationService;
    @Mock private GpsStreamingService gpsStreamingService;

    @InjectMocks private DispatchOrchestrator orchestrator;

    private CrashEventMessage crashEvent;
    private ResponderLocation nearestResponder;
    private final UUID incidentId = UUID.randomUUID();
    private final UUID responderId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        crashEvent = CrashEventMessage.builder()
                .eventType("CRASH_DETECTED")
                .crashEventId(UUID.randomUUID().toString())
                .driverId(driverId.toString())
                .driverName("John Doe")
                .driverPhone("+14155551234")
                .gpsLat(37.7749).gpsLng(-122.4194)
                .priorityScore(4).source("HTTP")
                .timestamp(Instant.now()).build();

        nearestResponder = ResponderLocation.builder()
                .responderId(responderId)
                .userName("Responder A").vehicleId("AMB-001")
                .latitude(37.78).longitude(-122.42)
                .dutyStatus("ON_DUTY").build();
    }

    // ══════════════════ CRASH_DETECTED ══════════════════

    @Test
    @DisplayName("handleCrashDetected — creates incident, fetches profile, dispatches")
    void handleCrashDetected_fullPipeline() {
        MedicalProfileDto profile = MedicalProfileDto.builder()
                .bloodType("O+").allergies("Penicillin").build();

        List<EmergencyContactDto> contacts = List.of(
                EmergencyContactDto.builder().contactName("Mom").phone("+1111").build());

        when(authServiceClient.getMedicalProfile(driverId)).thenReturn(profile);
        when(authServiceClient.getEmergencyContacts(driverId)).thenReturn(contacts);
        when(incidentRepository.save(any(Incident.class))).thenAnswer(inv -> {
            Incident i = inv.getArgument(0);
            i.setId(incidentId);
            return i;
        });
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), anyList()))
                .thenReturn(nearestResponder);
        when(attemptRepository.countByIncidentId(any())).thenReturn(0);
        when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orchestrator.handleCrashDetected(crashEvent);

        // Verify incident was created with medical data
        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository, atLeast(1)).save(captor.capture());
        Incident saved = captor.getAllValues().get(0);
        assertThat(saved.getBloodType()).isEqualTo("O+");
        assertThat(saved.getDriverName()).isEqualTo("John Doe");

        // Verify SMS sent to contacts
        verify(notificationService).sendEmergencySms(eq(contacts), eq("John Doe"), any());

        // Verify push sent to responder
        verify(notificationService).sendResponderPush(eq(responderId), eq("AMB-001"),
                any(), eq("John Doe"), anyDouble(), anyDouble(), eq(4));

        // Verify escalation timer started
        verify(escalationTimerService).startEscalationTimer(any(), any());
    }

    @Test
    @DisplayName("handleCrashDetected — works with fallback when Auth is down")
    void handleCrashDetected_authDown_fallback() {
        when(authServiceClient.getMedicalProfile(driverId)).thenReturn(
                MedicalProfileDto.builder().bloodType("UNKNOWN").allergies("UNKNOWN").build());
        when(authServiceClient.getEmergencyContacts(driverId)).thenReturn(Collections.emptyList());
        when(incidentRepository.save(any())).thenAnswer(inv -> {
            Incident i = inv.getArgument(0);
            i.setId(incidentId);
            return i;
        });
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), anyList()))
                .thenReturn(nearestResponder);
        when(attemptRepository.countByIncidentId(any())).thenReturn(0);
        when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orchestrator.handleCrashDetected(crashEvent);

        // SMS should NOT be sent (empty contacts from fallback)
        verify(notificationService, never()).sendEmergencySms(anyList(), anyString(), any());
        // But dispatch should still proceed
        verify(notificationService).sendResponderPush(any(), any(), any(), any(), anyDouble(), anyDouble(), anyInt());
    }

    // ══════════════════ CRASH_CANCELLED ══════════════════

    @Test
    @DisplayName("handleCrashCancelled — stands down responder, sends false alarm SMS")
    void handleCrashCancelled_standsDown() {
        Incident incident = Incident.builder()
                .id(incidentId).crashEventId(UUID.fromString(crashEvent.getCrashEventId()))
                .driverId(driverId).driverName("John Doe")
                .assignedResponderId(responderId).status(IncidentStatus.DISPATCHED).build();

        CrashEventMessage cancelEvent = CrashEventMessage.builder()
                .eventType("CRASH_CANCELLED")
                .crashEventId(crashEvent.getCrashEventId())
                .reason("LATE_CANCEL").build();

        List<EmergencyContactDto> contacts = List.of(
                EmergencyContactDto.builder().phone("+1111").contactName("Mom").build());

        when(incidentRepository.findByCrashEventId(any())).thenReturn(Optional.of(incident));
        when(authServiceClient.getEmergencyContacts(driverId)).thenReturn(contacts);
        when(incidentRepository.save(any())).thenReturn(incident);

        orchestrator.handleCrashCancelled(cancelEvent);

        verify(escalationTimerService).cancelTimer(incidentId);
        verify(notificationService).sendStandDownPush(responderId, incidentId);
        verify(notificationService).sendFalseAlarmSms(contacts, "John Doe");
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.CANCELLED_BY_USER);
    }

    // ══════════════════ ACCEPT ══════════════════

    @Test
    @DisplayName("acceptDispatch — sets ACCEPTED status, cancels escalation, marks ON_MISSION")
    void acceptDispatch_success() {
        Incident incident = Incident.builder()
                .id(incidentId).status(IncidentStatus.DISPATCHED).build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any())).thenReturn(incident);
        when(attemptRepository.findByIncidentIdOrderByAttemptNumber(incidentId))
                .thenReturn(List.of(DispatchAttempt.builder()
                        .responderId(responderId).response(DispatchResponseType.PENDING).build()));
        when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DispatchAckResponse response = orchestrator.acceptDispatch(incidentId, responderId.toString());

        assertThat(response.getStatus()).isEqualTo("ACCEPTED");
        assertThat(incident.getAssignedResponderId()).isEqualTo(responderId);
        verify(escalationTimerService).cancelTimer(incidentId);
        verify(spatialQueryService).markResponderOnMission(responderId);
    }

    // ══════════════════ REJECT ══════════════════

    @Test
    @DisplayName("rejectDispatch — escalates to next responder")
    void rejectDispatch_escalates() {
        Incident incident = Incident.builder()
                .id(incidentId).status(IncidentStatus.DISPATCHED)
                .gpsLat(37.77).gpsLng(-122.41).driverName("John").priorityScore(4).build();

        UUID nextResponderId = UUID.randomUUID();
        ResponderLocation nextResponder = ResponderLocation.builder()
                .responderId(nextResponderId).userName("Responder B")
                .vehicleId("AMB-002").latitude(37.79).longitude(-122.43).build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(attemptRepository.findByIncidentIdOrderByAttemptNumber(incidentId))
                .thenReturn(List.of(
                        DispatchAttempt.builder().responderId(responderId)
                                .response(DispatchResponseType.PENDING).build()));
        when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), anyList()))
                .thenReturn(nextResponder);
        when(attemptRepository.countByIncidentId(incidentId)).thenReturn(1);
        when(incidentRepository.save(any())).thenReturn(incident);

        DispatchAckResponse response = orchestrator.rejectDispatch(incidentId, responderId.toString());

        assertThat(response.getStatus()).isEqualTo("REJECTED");
        verify(notificationService).sendResponderPush(eq(nextResponderId), eq("AMB-002"),
                any(), any(), anyDouble(), anyDouble(), anyInt());
    }

    // ══════════════════ STATUS TRANSITIONS ══════════════════

    @Test
    @DisplayName("updateIncidentStatus — ARRIVED sets timestamp")
    void statusTransition_arrived() {
        Incident incident = Incident.builder()
                .id(incidentId).status(IncidentStatus.ACCEPTED).build();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any())).thenReturn(incident);

        orchestrator.updateIncidentStatus(incidentId, IncidentStatus.ARRIVED, null);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ARRIVED);
        assertThat(incident.getArrivedAt()).isNotNull();
    }

    @Test
    @DisplayName("updateIncidentStatus — EN_ROUTE_HOSPITAL sends hospital pre-alert")
    void statusTransition_enRoute() {
        Incident incident = Incident.builder()
                .id(incidentId).status(IncidentStatus.ARRIVED)
                .bloodType("O+").allergies("None").build();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any())).thenReturn(incident);

        orchestrator.updateIncidentStatus(incidentId, IncidentStatus.EN_ROUTE_HOSPITAL, "HOSP-1");

        verify(notificationService).sendHospitalPreAlert("HOSP-1", incidentId, "O+", "None", "Calculating...");
    }

    @Test
    @DisplayName("updateIncidentStatus — RESOLVED cleans up GPS and resets responder")
    void statusTransition_resolved() {
        Incident incident = Incident.builder()
                .id(incidentId).status(IncidentStatus.EN_ROUTE_HOSPITAL)
                .assignedResponderId(responderId).build();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any())).thenReturn(incident);

        orchestrator.updateIncidentStatus(incidentId, IncidentStatus.RESOLVED, null);

        assertThat(incident.getResolvedAt()).isNotNull();
        verify(gpsStreamingService).cleanupIncident(incidentId.toString());
        verify(spatialQueryService).markResponderOnDuty(responderId);
    }

    // ══════════════════ BYSTANDER ══════════════════

    @Test
    @DisplayName("markBystanderAssisting — sets flag on incident")
    void bystanderAssist() {
        Incident incident = Incident.builder()
                .id(incidentId).bystanderAssisting(false).build();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any())).thenReturn(incident);

        orchestrator.markBystanderAssisting(incidentId);

        assertThat(incident.getBystanderAssisting()).isTrue();
    }
}
