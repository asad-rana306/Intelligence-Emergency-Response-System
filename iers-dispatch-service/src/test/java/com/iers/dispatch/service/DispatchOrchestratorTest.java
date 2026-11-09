package com.iers.dispatch.service;

import com.iers.dispatch.dto.Viewer;
import com.iers.dispatch.dto.feign.EmergencyContactDto;
import com.iers.dispatch.dto.feign.MedicalProfileDto;
import com.iers.dispatch.dto.kafka.CrashEventMessage;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
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
    @Mock private IncidentEventPublisher eventPublisher;

    @Captor private ArgumentCaptor<Map<String, Object>> dataCaptor;

    @InjectMocks private DispatchOrchestrator orchestrator;

    private final UUID incidentId = UUID.randomUUID();
    private final UUID crashId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final UUID responderId = UUID.randomUUID();   // A: the first responder
    private final UUID otherResponderId = UUID.randomUUID(); // B: the next responder

    private CrashEventMessage crashEvent;
    private ResponderLocation responderA;
    private ResponderLocation responderB;
    private Incident saved; // what the fake save() stores

    @BeforeEach
    void setUp() {
        crashEvent = CrashEventMessage.builder()
                .eventType("CRASH_DETECTED")
                .crashEventId(crashId.toString())
                .driverId(driverId.toString())
                .driverName("John Doe").driverPhone("+14155551234")
                .gpsLat(37.7749).gpsLng(-122.4194)
                .priorityScore(4).source("HTTP")
                .timestamp(Instant.now()).build();

        responderA = ResponderLocation.builder()
                .responderId(responderId).userName("Responder A").phone("+923001111111")
                .vehicleId("AMB-001").latitude(37.78).longitude(-122.42).dutyStatus("ON_DUTY").build();
        responderB = ResponderLocation.builder()
                .responderId(otherResponderId).userName("Responder B").phone("+923002222222")
                .vehicleId("AMB-002").latitude(37.79).longitude(-122.43).dutyStatus("ON_DUTY").build();
    }

    // ─────────────── helpers ───────────────

    private Incident incident(IncidentStatus status, UUID assigned) {
        return Incident.builder()
                .id(incidentId).crashEventId(crashId).driverId(driverId)
                .driverName("John Doe").driverPhone("+14155551234").priorityScore(4)
                .gpsLat(37.7749).gpsLng(-122.4194).status(status)
                .assignedResponderId(assigned)
                .responderName("Responder A").responderPhone("+923001111111").responderVehicleId("AMB-001")
                .bloodType("O+").allergies("Penicillin")
                .createdAt(Instant.now()).build();
    }

    private void stubFindById(Incident incident) {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
    }

    private void stubClaim(int result) {
        when(incidentRepository.claimOffer(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result);
    }

    private DispatchAttempt pendingAttempt(UUID forResponder) {
        return DispatchAttempt.builder().incidentId(incidentId).responderId(forResponder)
                .attemptNumber(1).response(DispatchResponseType.PENDING).build();
    }

    /** Fake save(): gives the incident an id and remembers it, like the database would. */
    private void stubSaveAndFind() {
        when(incidentRepository.save(any(Incident.class))).thenAnswer(inv -> {
            saved = inv.getArgument(0);
            saved.setId(incidentId);
            return saved;
        });
        when(incidentRepository.findById(incidentId)).thenAnswer(inv -> Optional.of(saved));
    }

    // ══════════════════ CRASH_DETECTED ══════════════════

    @Test
    @DisplayName("handleCrashDetected — creates incident, announces it, offers it to the nearest responder")
    void handleCrashDetected_fullPipeline() {
        List<EmergencyContactDto> contacts = List.of(
                EmergencyContactDto.builder().contactName("Mom").phone("+1111").build());
        when(authServiceClient.getMedicalProfile(driverId)).thenReturn(
                MedicalProfileDto.builder().bloodType("O+").allergies("Penicillin").build());
        when(authServiceClient.getEmergencyContacts(driverId)).thenReturn(contacts);
        stubSaveAndFind();
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), anyList())).thenReturn(responderA);
        stubClaim(1);

        orchestrator.handleCrashDetected(crashEvent);

        // The incident was created with the medical snapshot and a safety retry time
        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        Incident created = captor.getValue();
        assertThat(created.getBloodType()).isEqualTo("O+");
        assertThat(created.getDriverName()).isEqualTo("John Doe");
        assertThat(created.getStatus()).isEqualTo(IncidentStatus.DISPATCHING);
        assertThat(created.getNextRetryAt()).isNotNull();

        // Announced: created, then offered
        InOrder order = inOrder(eventPublisher);
        order.verify(eventPublisher).publish(eq("INCIDENT_CREATED"), any(Incident.class), isNull(), dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("incidentId", incidentId.toString())
                .containsEntry("crashEventId", crashId.toString());
        order.verify(eventPublisher).publish(eq("INCIDENT_OFFERED"), any(Incident.class), eq(responderId), dataCaptor.capture());
        Map<String, Object> offered = dataCaptor.getValue();
        assertThat(offered).containsEntry("driverName", "John Doe")
                .containsEntry("bloodType", "O+")
                .containsEntry("allergies", "Penicillin")
                .containsEntry("priorityScore", 4)
                .containsEntry("attempt", 1);
        assertThat(offered.get("dispatchExpiresAt")).isInstanceOf(String.class);

        verify(notificationService).sendEmergencySms(eq(contacts), eq("John Doe"), any());
        verify(notificationService).sendResponderPush(eq(responderId), eq("AMB-001"),
                eq(incidentId), eq("John Doe"), eq(37.7749), eq(-122.4194), eq(4));
        verify(incidentRepository).claimOffer(eq(incidentId), eq(IncidentStatus.DISPATCHING),
                eq(IncidentStatus.DISPATCHED), eq(responderId), eq("Responder A"), eq("+923001111111"),
                eq("AMB-001"), any(Instant.class));
        verify(attemptRepository).save(any(DispatchAttempt.class));
        verify(escalationTimerService).startEscalationTimer(eq(incidentId), eq(30L), any(Runnable.class));
    }

    @Test
    @DisplayName("handleCrashDetected — a repeated Kafka message does not create a second incident")
    void handleCrashDetected_duplicate() {
        when(incidentRepository.findByCrashEventId(crashId))
                .thenReturn(Optional.of(incident(IncidentStatus.DISPATCHED, responderId)));

        orchestrator.handleCrashDetected(crashEvent);

        verify(incidentRepository, never()).save(any());
        verifyNoInteractions(authServiceClient, eventPublisher, spatialQueryService);
    }

    @Test
    @DisplayName("handleCrashDetected — the driver already has an open incident: the new crash is ignored")
    void handleCrashDetected_driverAlreadyHasOpenIncident() {
        when(incidentRepository.findFirstByDriverIdAndStatusInOrderByCreatedAtDesc(
                eq(driverId), eq(IncidentService.ACTIVE)))
                .thenReturn(Optional.of(incident(IncidentStatus.DISPATCHED, responderId)));

        orchestrator.handleCrashDetected(crashEvent);

        verify(incidentRepository, never()).save(any());
        verifyNoInteractions(authServiceClient, eventPublisher, spatialQueryService,
                notificationService, escalationTimerService);
    }

    @Test
    @DisplayName("handleCrashDetected — the driver's last incident is finished: a new crash is handled normally")
    void handleCrashDetected_previousIncidentFinished() {
        when(incidentRepository.findFirstByDriverIdAndStatusInOrderByCreatedAtDesc(
                eq(driverId), eq(IncidentService.ACTIVE))).thenReturn(Optional.empty());
        when(authServiceClient.getMedicalProfile(driverId)).thenReturn(
                MedicalProfileDto.builder().bloodType("O+").allergies("None").build());
        when(authServiceClient.getEmergencyContacts(driverId)).thenReturn(Collections.emptyList());
        stubSaveAndFind();
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), anyList())).thenReturn(responderA);
        stubClaim(1);

        orchestrator.handleCrashDetected(crashEvent);

        verify(incidentRepository).save(any(Incident.class));
        verify(eventPublisher).publish(eq("INCIDENT_CREATED"), any(Incident.class), isNull(), anyMap());
    }

    @Test
    @DisplayName("handleCrashDetected — Auth down: medical UNKNOWN, no SMS, dispatch still happens")
    void handleCrashDetected_authDown_fallback() {
        when(authServiceClient.getMedicalProfile(driverId)).thenReturn(
                MedicalProfileDto.builder().bloodType("UNKNOWN").allergies("UNKNOWN").build());
        when(authServiceClient.getEmergencyContacts(driverId)).thenReturn(Collections.emptyList());
        stubSaveAndFind();
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), anyList())).thenReturn(responderA);
        stubClaim(1);

        orchestrator.handleCrashDetected(crashEvent);

        verify(notificationService, never()).sendEmergencySms(anyList(), anyString(), any());
        verify(notificationService).sendResponderPush(any(), any(), any(), any(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("handleCrashDetected — crash without a driver id still gets an incident")
    void handleCrashDetected_noDriver() {
        crashEvent.setDriverId(null);
        stubSaveAndFind();
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), anyList())).thenReturn(responderA);
        stubClaim(1);

        orchestrator.handleCrashDetected(crashEvent);

        assertThat(saved.getBloodType()).isEqualTo("UNKNOWN");
        verifyNoInteractions(authServiceClient);
        verify(notificationService).sendResponderPush(any(), any(), any(), any(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("handleCrashDetected — nobody free: no push, a retry is scheduled")
    void handleCrashDetected_noResponder() {
        when(authServiceClient.getMedicalProfile(driverId)).thenReturn(
                MedicalProfileDto.builder().bloodType("O+").allergies("None").build());
        when(authServiceClient.getEmergencyContacts(driverId)).thenReturn(Collections.emptyList());
        stubSaveAndFind();
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), anyList())).thenReturn(null);

        orchestrator.handleCrashDetected(crashEvent);

        verify(incidentRepository).scheduleRetry(eq(incidentId), eq(IncidentStatus.DISPATCHING), any(Instant.class));
        verify(incidentRepository, never()).claimOffer(any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(escalationTimerService, notificationService);
        // the driver still learns the incident exists
        verify(eventPublisher).publish(eq("INCIDENT_CREATED"), any(Incident.class), isNull(), anyMap());
    }

    // ══════════════════ DISPATCH ENGINE ══════════════════

    @Test
    @DisplayName("dispatchToNearest — an unknown incident is ignored")
    void dispatch_unknownIncident() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());
        orchestrator.dispatchToNearest(incidentId);
        verifyNoInteractions(spatialQueryService, eventPublisher);
    }

    @Test
    @DisplayName("dispatchToNearest — an incident that is not DISPATCHING is left alone")
    void dispatch_wrongState() {
        stubFindById(incident(IncidentStatus.ACCEPTED, responderId));
        orchestrator.dispatchToNearest(incidentId);
        verifyNoInteractions(spatialQueryService, eventPublisher);
    }

    @Test
    @DisplayName("dispatchToNearest — cancelled while choosing: the claim loses, nothing is sent")
    void dispatch_claimLost() {
        stubFindById(incident(IncidentStatus.DISPATCHING, null));
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), anyList())).thenReturn(responderA);
        stubClaim(0);

        orchestrator.dispatchToNearest(incidentId);

        verify(attemptRepository, never()).save(any());
        verifyNoInteractions(notificationService, escalationTimerService, eventPublisher);
    }

    @Test
    @DisplayName("dispatchToNearest — no GPS position: cannot choose, retries later")
    void dispatch_noGps() {
        Incident noGps = incident(IncidentStatus.DISPATCHING, null);
        noGps.setGpsLat(null);
        stubFindById(noGps);

        orchestrator.dispatchToNearest(incidentId);

        verifyNoInteractions(spatialQueryService);
        verify(incidentRepository).scheduleRetry(eq(incidentId), eq(IncidentStatus.DISPATCHING), any(Instant.class));
    }

    @Test
    @DisplayName("dispatchToNearest — after 2 minutes with no accept, NO_RESPONDER_AVAILABLE is sent once")
    void dispatch_noResponderAnnounced() {
        Incident old = incident(IncidentStatus.DISPATCHING, null);
        old.setCreatedAt(Instant.now().minusSeconds(130));
        stubFindById(old);
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), anyList())).thenReturn(null);
        when(incidentRepository.markNoResponderNotified(incidentId, IncidentStatus.DISPATCHING)).thenReturn(1);

        orchestrator.dispatchToNearest(incidentId);

        verify(eventPublisher).publish(eq("NO_RESPONDER_AVAILABLE"), any(Incident.class), isNull(), anyMap());
        verify(incidentRepository).scheduleRetry(eq(incidentId), eq(IncidentStatus.DISPATCHING), any(Instant.class));
    }

    @Test
    @DisplayName("dispatchToNearest — NO_RESPONDER_AVAILABLE is not repeated")
    void dispatch_noResponderNotRepeated() {
        Incident old = incident(IncidentStatus.DISPATCHING, null);
        old.setCreatedAt(Instant.now().minusSeconds(130));
        stubFindById(old);
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), anyList())).thenReturn(null);
        when(incidentRepository.markNoResponderNotified(incidentId, IncidentStatus.DISPATCHING)).thenReturn(0);

        orchestrator.dispatchToNearest(incidentId);

        verify(eventPublisher, never()).publish(eq("NO_RESPONDER_AVAILABLE"), any(), any(), any());
    }

    @Test
    @DisplayName("dispatchToNearest — under 2 minutes: no announcement")
    void dispatch_tooEarlyToAnnounce() {
        stubFindById(incident(IncidentStatus.DISPATCHING, null));
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), anyList())).thenReturn(null);

        orchestrator.dispatchToNearest(incidentId);

        verify(incidentRepository, never()).markNoResponderNotified(any(), any());
    }

    @Test
    @DisplayName("dispatchToNearest — incident older than 30 minutes: gives up")
    void dispatch_givesUp() {
        Incident ancient = incident(IncidentStatus.DISPATCHING, null);
        ancient.setCreatedAt(Instant.now().minusSeconds(31 * 60));
        stubFindById(ancient);

        orchestrator.dispatchToNearest(incidentId);

        verify(incidentRepository).stopRetrying(incidentId, IncidentStatus.DISPATCHING);
        verifyNoInteractions(spatialQueryService, eventPublisher);
    }

    @Test
    @DisplayName("dispatchToNearest — asks a responder who timed out again, only when nobody new is left")
    void dispatch_reofferTimedOut() {
        stubFindById(incident(IncidentStatus.DISPATCHING, null));
        DispatchAttempt timedOut = pendingAttempt(responderId);
        timedOut.setResponse(DispatchResponseType.TIMED_OUT);
        when(attemptRepository.findByIncidentIdOrderByAttemptNumber(incidentId)).thenReturn(List.of(timedOut));
        when(attemptRepository.countByIncidentId(incidentId)).thenReturn(1);
        // first search (skip everybody already asked) finds nobody, second (skip only rejections) finds A
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), eq(List.of(responderId))))
                .thenReturn(null);
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), eq(List.of())))
                .thenReturn(responderA);
        stubClaim(1);

        orchestrator.dispatchToNearest(incidentId);

        verify(eventPublisher).publish(eq("INCIDENT_OFFERED"), any(Incident.class), eq(responderId), dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("attempt", 2);
    }

    @Test
    @DisplayName("dispatchToNearest — a responder who rejected is never asked again")
    void dispatch_neverReofferRejected() {
        stubFindById(incident(IncidentStatus.DISPATCHING, null));
        DispatchAttempt rejected = pendingAttempt(responderId);
        rejected.setResponse(DispatchResponseType.REJECTED);
        when(attemptRepository.findByIncidentIdOrderByAttemptNumber(incidentId)).thenReturn(List.of(rejected));
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), eq(List.of(responderId))))
                .thenReturn(null);

        orchestrator.dispatchToNearest(incidentId);

        verify(spatialQueryService, times(1)).findNearestResponder(anyDouble(), anyDouble(), anyList());
        verify(incidentRepository).scheduleRetry(eq(incidentId), eq(IncidentStatus.DISPATCHING), any(Instant.class));
    }

    @Test
    @DisplayName("dispatchToNearest — a missing priority is sent as 0")
    void dispatch_nullPriority() {
        Incident noPriority = incident(IncidentStatus.DISPATCHING, null);
        noPriority.setPriorityScore(null);
        stubFindById(noPriority);
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), anyList())).thenReturn(responderA);
        stubClaim(1);

        orchestrator.dispatchToNearest(incidentId);

        verify(notificationService).sendResponderPush(any(), any(), any(), any(), anyDouble(), anyDouble(), eq(0));
    }

    @Test
    @DisplayName("retryDispatch — runs the normal search")
    void retryDispatch_runsSearch() {
        stubFindById(incident(IncidentStatus.DISPATCHING, null));
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), anyList())).thenReturn(responderA);
        stubClaim(1);

        orchestrator.retryDispatch(incidentId);

        verify(eventPublisher).publish(eq("INCIDENT_OFFERED"), any(Incident.class), eq(responderId), anyMap());
    }

    // ══════════════════ TIMEOUT ══════════════════

    @Test
    @DisplayName("onOfferTimeout — marks TIMED_OUT, tells the responder, offers to the next one")
    void timeout_escalates() {
        Incident inc = incident(IncidentStatus.DISPATCHED, responderId);
        stubFindById(inc);
        when(incidentRepository.releaseOffer(eq(incidentId), eq(responderId), eq(IncidentStatus.DISPATCHED),
                eq(IncidentStatus.DISPATCHING), any(Instant.class))).thenAnswer(inv -> {
            inc.setStatus(IncidentStatus.DISPATCHING);
            return 1;
        });
        DispatchAttempt attemptA = pendingAttempt(responderId);
        when(attemptRepository.findByIncidentIdOrderByAttemptNumber(incidentId)).thenReturn(List.of(attemptA));
        when(attemptRepository.countByIncidentId(incidentId)).thenReturn(1);
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), eq(List.of(responderId))))
                .thenReturn(responderB);
        stubClaim(1);

        orchestrator.onOfferTimeout(incidentId, responderId);

        assertThat(attemptA.getResponse()).isEqualTo(DispatchResponseType.TIMED_OUT);
        InOrder order = inOrder(eventPublisher);
        order.verify(eventPublisher).publish(eq("INCIDENT_OFFER_EXPIRED"), any(Incident.class), eq(responderId), anyMap());
        order.verify(eventPublisher).publish(eq("INCIDENT_OFFERED"), any(Incident.class), eq(otherResponderId), anyMap());
        verify(notificationService).sendResponderPush(eq(otherResponderId), eq("AMB-002"),
                any(), any(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("onOfferTimeout — responder already answered: nothing happens")
    void timeout_lostRace() {
        stubFindById(incident(IncidentStatus.ACCEPTED, responderId));
        when(incidentRepository.releaseOffer(any(), any(), any(), any(), any())).thenReturn(0);

        orchestrator.onOfferTimeout(incidentId, responderId);

        verifyNoInteractions(spatialQueryService, eventPublisher, notificationService);
    }

    @Test
    @DisplayName("onOfferTimeout — unknown incident is ignored")
    void timeout_unknownIncident() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());
        orchestrator.onOfferTimeout(incidentId, responderId);
        verify(incidentRepository, never()).releaseOffer(any(), any(), any(), any(), any());
    }

    // ══════════════════ CRASH_CANCELLED ══════════════════

    private CrashEventMessage cancelEvent() {
        return CrashEventMessage.builder().eventType("CRASH_CANCELLED")
                .crashEventId(crashId.toString()).reason("LATE_CANCEL").build();
    }

    @Test
    @DisplayName("handleCrashCancelled — stands down the responder, tells everyone, sends false-alarm SMS")
    void cancel_standsDown() {
        Incident inc = incident(IncidentStatus.DISPATCHED, responderId);
        List<EmergencyContactDto> contacts = List.of(
                EmergencyContactDto.builder().phone("+1111").contactName("Mom").build());
        when(incidentRepository.findByCrashEventId(crashId)).thenReturn(Optional.of(inc));
        when(incidentRepository.cancelIfActive(eq(incidentId), anyCollection(),
                eq(IncidentStatus.CANCELLED_BY_USER), any(Instant.class))).thenReturn(1);
        when(authServiceClient.getEmergencyContacts(driverId)).thenReturn(contacts);

        orchestrator.handleCrashCancelled(cancelEvent());

        verify(escalationTimerService).cancelTimer(incidentId);
        verify(notificationService).sendStandDownPush(responderId, incidentId);
        verify(spatialQueryService).markResponderOnDuty(responderId);
        verify(authServiceClient).updateResponderStatus(responderId, "ON_DUTY");
        verify(notificationService).sendFalseAlarmSms(contacts, "John Doe");
        verify(gpsStreamingService).cleanupIncident(incidentId.toString());
        verify(eventPublisher).publish(eq("INCIDENT_STAND_DOWN"), any(Incident.class), eq(responderId), dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("reason", "DRIVER_CANCELLED");
    }

    @Test
    @DisplayName("handleCrashCancelled — nobody holds the incident yet: no stand-down, SMS still sent")
    void cancel_noResponderYet() {
        Incident inc = incident(IncidentStatus.DISPATCHING, null);
        List<EmergencyContactDto> contacts = List.of(
                EmergencyContactDto.builder().phone("+1111").contactName("Mom").build());
        when(incidentRepository.findByCrashEventId(crashId)).thenReturn(Optional.of(inc));
        when(incidentRepository.cancelIfActive(any(), anyCollection(), any(), any())).thenReturn(1);
        when(authServiceClient.getEmergencyContacts(driverId)).thenReturn(contacts);

        orchestrator.handleCrashCancelled(cancelEvent());

        verify(notificationService, never()).sendStandDownPush(any(), any());
        verifyNoInteractions(eventPublisher, spatialQueryService);
        verify(notificationService).sendFalseAlarmSms(contacts, "John Doe");
    }

    @Test
    @DisplayName("handleCrashCancelled — no contacts (Auth down): no SMS")
    void cancel_noContacts() {
        Incident inc = incident(IncidentStatus.DISPATCHING, null);
        when(incidentRepository.findByCrashEventId(crashId)).thenReturn(Optional.of(inc));
        when(incidentRepository.cancelIfActive(any(), anyCollection(), any(), any())).thenReturn(1);
        when(authServiceClient.getEmergencyContacts(driverId)).thenReturn(Collections.emptyList());

        orchestrator.handleCrashCancelled(cancelEvent());

        verify(notificationService, never()).sendFalseAlarmSms(any(), any());
    }

    @Test
    @DisplayName("handleCrashCancelled — an incident without a driver id needs no contact lookup")
    void cancel_noDriver() {
        Incident inc = incident(IncidentStatus.DISPATCHING, null);
        inc.setDriverId(null);
        when(incidentRepository.findByCrashEventId(crashId)).thenReturn(Optional.of(inc));
        when(incidentRepository.cancelIfActive(any(), anyCollection(), any(), any())).thenReturn(1);

        orchestrator.handleCrashCancelled(cancelEvent());

        verifyNoInteractions(authServiceClient);
    }

    @Test
    @DisplayName("handleCrashCancelled — an incident that is already over is not touched")
    void cancel_alreadyFinished() {
        when(incidentRepository.findByCrashEventId(crashId))
                .thenReturn(Optional.of(incident(IncidentStatus.RESOLVED, responderId)));
        when(incidentRepository.cancelIfActive(any(), anyCollection(), any(), any())).thenReturn(0);

        orchestrator.handleCrashCancelled(cancelEvent());

        verifyNoInteractions(notificationService, eventPublisher, authServiceClient, escalationTimerService);
    }

    @Test
    @DisplayName("handleCrashCancelled — no incident for this crash: nothing happens")
    void cancel_noIncident() {
        when(incidentRepository.findByCrashEventId(crashId)).thenReturn(Optional.empty());
        orchestrator.handleCrashCancelled(cancelEvent());
        verify(incidentRepository, never()).cancelIfActive(any(), any(), any(), any());
    }

    // ══════════════════ ACCEPT ══════════════════

    @Test
    @DisplayName("acceptDispatch — offered responder: ACCEPTED, timer stopped, ON_MISSION, driver told")
    void accept_success() {
        stubFindById(incident(IncidentStatus.DISPATCHED, responderId));
        when(incidentRepository.acceptOffer(eq(incidentId), eq(responderId), eq(IncidentStatus.DISPATCHED),
                eq(IncidentStatus.ACCEPTED), any(Instant.class))).thenReturn(1);
        DispatchAttempt attempt = pendingAttempt(responderId);
        when(attemptRepository.findByIncidentIdOrderByAttemptNumber(incidentId)).thenReturn(List.of(attempt));

        DispatchAckResponse response = orchestrator.acceptDispatch(incidentId, responderId);

        assertThat(response.getStatus()).isEqualTo("ACCEPTED");
        assertThat(response.getIncidentId()).isEqualTo(incidentId.toString());
        assertThat(attempt.getResponse()).isEqualTo(DispatchResponseType.ACCEPTED);
        verify(escalationTimerService).cancelTimer(incidentId);
        verify(spatialQueryService).markResponderOnMission(responderId);
        verify(authServiceClient).updateResponderStatus(responderId, "ON_MISSION");

        verify(eventPublisher).publish(eq("INCIDENT_ACCEPTED"), any(Incident.class), eq(responderId), dataCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> responder = (Map<String, Object>) dataCaptor.getValue().get("responder");
        assertThat(responder).containsEntry("name", "Responder A")
                .containsEntry("phone", "+923001111111")
                .containsEntry("vehicleId", "AMB-001");
    }

    @Test
    @DisplayName("acceptDispatch — a failing Auth call does not stop the accept")
    void accept_authFails() {
        stubFindById(incident(IncidentStatus.DISPATCHED, responderId));
        when(incidentRepository.acceptOffer(any(), any(), any(), any(), any())).thenReturn(1);
        doThrow(new RuntimeException("auth down")).when(authServiceClient)
                .updateResponderStatus(any(), anyString());

        DispatchAckResponse response = orchestrator.acceptDispatch(incidentId, responderId);

        assertThat(response.getStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("acceptDispatch — a responder who was never offered it gets 403")
    void accept_wrongPerson() {
        stubFindById(incident(IncidentStatus.DISPATCHED, responderId));

        assertThatThrownBy(() -> orchestrator.acceptDispatch(incidentId, otherResponderId))
                .isInstanceOf(IncidentAccessDeniedException.class);

        verify(incidentRepository, never()).acceptOffer(any(), any(), any(), any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("acceptDispatch — a responder whose offer already expired gets 409, not 403")
    void accept_offerExpired() {
        stubFindById(incident(IncidentStatus.DISPATCHED, otherResponderId)); // now offered to B
        when(attemptRepository.existsByIncidentIdAndResponderId(incidentId, responderId)).thenReturn(true);

        assertThatThrownBy(() -> orchestrator.acceptDispatch(incidentId, responderId))
                .isInstanceOf(InvalidIncidentStateException.class);
    }

    @Test
    @DisplayName("acceptDispatch — a cancelled incident cannot be accepted (409)")
    void accept_cancelledIncident() {
        stubFindById(incident(IncidentStatus.CANCELLED_BY_USER, responderId));

        assertThatThrownBy(() -> orchestrator.acceptDispatch(incidentId, responderId))
                .isInstanceOf(InvalidIncidentStateException.class)
                .hasMessageContaining("CANCELLED_BY_USER");
        verify(incidentRepository, never()).acceptOffer(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("acceptDispatch — the offer is taken back at the last moment: 409, nothing announced")
    void accept_lostRace() {
        stubFindById(incident(IncidentStatus.DISPATCHED, responderId));
        when(incidentRepository.acceptOffer(any(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> orchestrator.acceptDispatch(incidentId, responderId))
                .isInstanceOf(InvalidIncidentStateException.class);

        verifyNoInteractions(eventPublisher, spatialQueryService, escalationTimerService);
    }

    @Test
    @DisplayName("acceptDispatch — unknown incident gives 404")
    void accept_unknownIncident() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> orchestrator.acceptDispatch(incidentId, responderId))
                .isInstanceOf(IncidentNotFoundException.class);
    }

    // ══════════════════ REJECT ══════════════════

    @Test
    @DisplayName("rejectDispatch — marks REJECTED and offers the incident to the next responder")
    void reject_escalates() {
        Incident inc = incident(IncidentStatus.DISPATCHED, responderId);
        stubFindById(inc);
        when(incidentRepository.releaseOffer(eq(incidentId), eq(responderId), eq(IncidentStatus.DISPATCHED),
                eq(IncidentStatus.DISPATCHING), any(Instant.class))).thenAnswer(inv -> {
            inc.setStatus(IncidentStatus.DISPATCHING);
            return 1;
        });
        DispatchAttempt attemptA = pendingAttempt(responderId);
        when(attemptRepository.findByIncidentIdOrderByAttemptNumber(incidentId)).thenReturn(List.of(attemptA));
        when(attemptRepository.countByIncidentId(incidentId)).thenReturn(1);
        when(spatialQueryService.findNearestResponder(anyDouble(), anyDouble(), eq(List.of(responderId))))
                .thenReturn(responderB);
        stubClaim(1);

        DispatchAckResponse response = orchestrator.rejectDispatch(incidentId, responderId);

        assertThat(response.getStatus()).isEqualTo("REJECTED");
        assertThat(attemptA.getResponse()).isEqualTo(DispatchResponseType.REJECTED);
        verify(escalationTimerService).cancelTimer(incidentId);
        verify(eventPublisher).publish(eq("INCIDENT_OFFER_EXPIRED"), any(Incident.class), eq(responderId), anyMap());
        verify(notificationService).sendResponderPush(eq(otherResponderId), eq("AMB-002"),
                any(), any(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("rejectDispatch — a responder who was never offered it gets 403")
    void reject_wrongPerson() {
        stubFindById(incident(IncidentStatus.DISPATCHED, responderId));
        assertThatThrownBy(() -> orchestrator.rejectDispatch(incidentId, otherResponderId))
                .isInstanceOf(IncidentAccessDeniedException.class);
        verify(incidentRepository, never()).releaseOffer(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("rejectDispatch — the offer is already gone: 409")
    void reject_lostRace() {
        stubFindById(incident(IncidentStatus.DISPATCHED, responderId));
        when(incidentRepository.releaseOffer(any(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> orchestrator.rejectDispatch(incidentId, responderId))
                .isInstanceOf(InvalidIncidentStateException.class);
        verifyNoInteractions(eventPublisher, spatialQueryService);
    }

    // ══════════════════ STATUS TRANSITIONS ══════════════════

    @Test
    @DisplayName("updateIncidentStatus — ARRIVED")
    void status_arrived() {
        stubFindById(incident(IncidentStatus.ACCEPTED, responderId));
        when(incidentRepository.markArrived(eq(incidentId), eq(responderId), eq(IncidentStatus.ACCEPTED),
                eq(IncidentStatus.ARRIVED), any(Instant.class))).thenReturn(1);

        DispatchAckResponse response = orchestrator.updateIncidentStatus(
                incidentId, responderId, IncidentStatus.ARRIVED, null);

        assertThat(response.getStatus()).isEqualTo("ARRIVED");
        verify(eventPublisher).publish(eq("INCIDENT_STATUS"), any(Incident.class), eq(responderId), dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("status", "ARRIVED");
    }

    @Test
    @DisplayName("updateIncidentStatus — EN_ROUTE_HOSPITAL sends the hospital pre-alert")
    void status_enRoute() {
        stubFindById(incident(IncidentStatus.ARRIVED, responderId));
        when(incidentRepository.markEnRoute(eq(incidentId), eq(responderId), eq(IncidentStatus.ARRIVED),
                eq(IncidentStatus.EN_ROUTE_HOSPITAL), any(Instant.class), eq("HOSP-1"))).thenReturn(1);

        orchestrator.updateIncidentStatus(incidentId, responderId, IncidentStatus.EN_ROUTE_HOSPITAL, "HOSP-1");

        verify(notificationService).sendHospitalPreAlert("HOSP-1", incidentId, "O+", "Penicillin", "Calculating...");
    }

    @Test
    @DisplayName("updateIncidentStatus — RESOLVED straight from ARRIVED cleans up and frees the responder")
    void status_resolved() {
        stubFindById(incident(IncidentStatus.ARRIVED, responderId));
        when(incidentRepository.markResolved(eq(incidentId), eq(responderId),
                eq(List.of(IncidentStatus.ARRIVED, IncidentStatus.EN_ROUTE_HOSPITAL)),
                eq(IncidentStatus.RESOLVED), any(Instant.class))).thenReturn(1);

        orchestrator.updateIncidentStatus(incidentId, responderId, IncidentStatus.RESOLVED, null);

        verify(gpsStreamingService).cleanupIncident(incidentId.toString());
        verify(spatialQueryService).markResponderOnDuty(responderId);
        verify(authServiceClient).updateResponderStatus(responderId, "ON_DUTY");
    }

    @Test
    @DisplayName("updateIncidentStatus — someone who is not the assigned responder gets 403")
    void status_wrongPerson() {
        stubFindById(incident(IncidentStatus.ACCEPTED, responderId));
        assertThatThrownBy(() -> orchestrator.updateIncidentStatus(
                incidentId, otherResponderId, IncidentStatus.ARRIVED, null))
                .isInstanceOf(IncidentAccessDeniedException.class);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("updateIncidentStatus — a step out of order gets 409")
    void status_wrongOrder() {
        stubFindById(incident(IncidentStatus.ACCEPTED, responderId));
        when(incidentRepository.markEnRoute(any(), any(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> orchestrator.updateIncidentStatus(
                incidentId, responderId, IncidentStatus.EN_ROUTE_HOSPITAL, "HOSP-1"))
                .isInstanceOf(InvalidIncidentStateException.class)
                .hasMessageContaining("ACCEPTED");
        verifyNoInteractions(eventPublisher, notificationService);
    }

    @Test
    @DisplayName("updateIncidentStatus — a status this endpoint does not set gives 400")
    void status_invalidValue() {
        stubFindById(incident(IncidentStatus.ACCEPTED, responderId));
        assertThatThrownBy(() -> orchestrator.updateIncidentStatus(
                incidentId, responderId, IncidentStatus.CANCELLED_BY_USER, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("updateIncidentStatus — unknown incident gives 404")
    void status_unknownIncident() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> orchestrator.updateIncidentStatus(
                incidentId, responderId, IncidentStatus.ARRIVED, null))
                .isInstanceOf(IncidentNotFoundException.class);
    }

    // ══════════════════ BYSTANDER ══════════════════

    @Test
    @DisplayName("markBystanderAssisting — the driver's phone taps it: responder is told")
    void bystander_first() {
        stubFindById(incident(IncidentStatus.ACCEPTED, responderId));
        when(incidentRepository.markBystanderAssisting(eq(incidentId), anyCollection())).thenReturn(1);

        DispatchAckResponse response = orchestrator.markBystanderAssisting(
                incidentId, new Viewer(driverId, "DRIVER"));

        assertThat(response.getStatus()).isEqualTo("BYSTANDER_NOTIFIED");
        verify(eventPublisher).publish(eq("BYSTANDER_ASSISTING"), any(Incident.class), eq(responderId), anyMap());
    }

    @Test
    @DisplayName("markBystanderAssisting — a second tap is accepted quietly")
    void bystander_repeat() {
        stubFindById(incident(IncidentStatus.ACCEPTED, responderId));
        when(incidentRepository.markBystanderAssisting(eq(incidentId), anyCollection())).thenReturn(0);

        DispatchAckResponse response = orchestrator.markBystanderAssisting(
                incidentId, new Viewer(driverId, "DRIVER"));

        assertThat(response.getStatus()).isEqualTo("BYSTANDER_NOTIFIED");
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("markBystanderAssisting — a stranger gets 403")
    void bystander_stranger() {
        stubFindById(incident(IncidentStatus.ACCEPTED, responderId));
        assertThatThrownBy(() -> orchestrator.markBystanderAssisting(
                incidentId, new Viewer(UUID.randomUUID(), "DRIVER")))
                .isInstanceOf(IncidentAccessDeniedException.class);
    }

    @Test
    @DisplayName("markBystanderAssisting — a finished incident gives 409")
    void bystander_finished() {
        stubFindById(incident(IncidentStatus.RESOLVED, responderId));
        assertThatThrownBy(() -> orchestrator.markBystanderAssisting(
                incidentId, new Viewer(driverId, "DRIVER")))
                .isInstanceOf(InvalidIncidentStateException.class);
        verify(incidentRepository, never()).markBystanderAssisting(any(), any());
    }

    @Test
    @DisplayName("markBystanderAssisting — unknown incident gives 404")
    void bystander_unknown() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> orchestrator.markBystanderAssisting(
                incidentId, new Viewer(driverId, "DRIVER")))
                .isInstanceOf(IncidentNotFoundException.class);
    }

    // ══════════════════ PHASE 6: accept again from history ══════════════════

    @Test
    @DisplayName("reaccept — still my open offer: a normal accept")
    void reaccept_ownOffer() {
        stubFindById(incident(IncidentStatus.DISPATCHED, responderId));
        when(incidentRepository.acceptOffer(eq(incidentId), eq(responderId), eq(IncidentStatus.DISPATCHED),
                eq(IncidentStatus.ACCEPTED), any(Instant.class))).thenReturn(1);

        DispatchAckResponse r = orchestrator.reacceptDispatch(incidentId, responderId);

        assertThat(r.getStatus()).isEqualTo("ACCEPTED");
        verify(incidentRepository, never()).claimOffer(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reaccept — never offered to me: 403")
    void reaccept_neverOffered() {
        stubFindById(incident(IncidentStatus.DISPATCHING, null));
        when(attemptRepository.existsByIncidentIdAndResponderId(incidentId, responderId)).thenReturn(false);

        assertThatThrownBy(() -> orchestrator.reacceptDispatch(incidentId, responderId))
                .isInstanceOf(IncidentAccessDeniedException.class);
    }

    @Test
    @DisplayName("reaccept — somebody else already accepted: 409")
    void reaccept_alreadyTaken() {
        stubFindById(incident(IncidentStatus.ACCEPTED, otherResponderId));
        when(attemptRepository.existsByIncidentIdAndResponderId(incidentId, responderId)).thenReturn(true);

        assertThatThrownBy(() -> orchestrator.reacceptDispatch(incidentId, responderId))
                .isInstanceOf(InvalidIncidentStateException.class);
        verify(incidentRepository, never()).claimOffer(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reaccept — I am busy with another emergency: 409")
    void reaccept_busy() {
        stubFindById(incident(IncidentStatus.DISPATCHING, null));
        when(attemptRepository.existsByIncidentIdAndResponderId(incidentId, responderId)).thenReturn(true);
        when(incidentRepository.findFirstByAssignedResponderIdAndStatusInOrderByCreatedAtDesc(
                eq(responderId), eq(IncidentService.ACCEPTED_AND_ON)))
                .thenReturn(Optional.of(incident(IncidentStatus.ACCEPTED, responderId)));

        assertThatThrownBy(() -> orchestrator.reacceptDispatch(incidentId, responderId))
                .isInstanceOf(InvalidIncidentStateException.class);
    }

    @Test
    @DisplayName("reaccept — another responder is being asked: their offer is taken back, I get it ACCEPTED")
    void reaccept_takesBackOffer() {
        Incident askingOther = incident(IncidentStatus.DISPATCHED, otherResponderId);
        Incident waiting = incident(IncidentStatus.DISPATCHING, null);
        Incident offeredToMe = incident(IncidentStatus.DISPATCHED, responderId);
        when(incidentRepository.findById(incidentId)).thenReturn(
                Optional.of(askingOther), Optional.of(waiting), Optional.of(offeredToMe));
        when(attemptRepository.existsByIncidentIdAndResponderId(incidentId, responderId)).thenReturn(true);
        when(incidentRepository.releaseOffer(eq(incidentId), eq(otherResponderId), eq(IncidentStatus.DISPATCHED),
                eq(IncidentStatus.DISPATCHING), any(Instant.class))).thenReturn(1);
        when(spatialQueryService.findLocation(responderId)).thenReturn(responderA);
        stubClaim(1);
        when(incidentRepository.acceptOffer(eq(incidentId), eq(responderId), eq(IncidentStatus.DISPATCHED),
                eq(IncidentStatus.ACCEPTED), any(Instant.class))).thenReturn(1);

        DispatchAckResponse r = orchestrator.reacceptDispatch(incidentId, responderId);

        assertThat(r.getStatus()).isEqualTo("ACCEPTED");
        verify(eventPublisher).publish(eq("INCIDENT_OFFER_EXPIRED"), any(Incident.class), eq(otherResponderId), anyMap());
        verify(incidentRepository).claimOffer(eq(incidentId), eq(IncidentStatus.DISPATCHING),
                eq(IncidentStatus.DISPATCHED), eq(responderId), eq("Responder A"), eq("+923001111111"),
                eq("AMB-001"), any(Instant.class));
        verify(eventPublisher).publish(eq("INCIDENT_ACCEPTED"), any(Incident.class), eq(responderId), anyMap());
        verify(attemptRepository).save(argThat(a -> a.getResponderId().equals(responderId)
                && a.getResponse() == DispatchResponseType.PENDING));
    }

    @Test
    @DisplayName("reaccept — Dispatch never saw my vehicle: 409, nothing claimed")
    void reaccept_noLocation() {
        stubFindById(incident(IncidentStatus.DISPATCHING, null));
        when(attemptRepository.existsByIncidentIdAndResponderId(incidentId, responderId)).thenReturn(true);
        when(spatialQueryService.findLocation(responderId)).thenReturn(null);

        assertThatThrownBy(() -> orchestrator.reacceptDispatch(incidentId, responderId))
                .isInstanceOf(InvalidIncidentStateException.class);
        verify(incidentRepository, never()).claimOffer(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
