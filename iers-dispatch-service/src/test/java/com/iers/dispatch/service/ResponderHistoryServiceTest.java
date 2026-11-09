package com.iers.dispatch.service;

import com.iers.dispatch.dto.response.ResponderHistoryItem;
import com.iers.dispatch.entity.DispatchAttempt;
import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.entity.enums.DispatchResponseType;
import com.iers.dispatch.entity.enums.IncidentStatus;
import com.iers.dispatch.repository.DispatchAttemptRepository;
import com.iers.dispatch.repository.IncidentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResponderHistoryServiceTest {

    @Mock private DispatchAttemptRepository attemptRepository;
    @Mock private IncidentRepository incidentRepository;
    @InjectMocks private ResponderHistoryService historyService;

    private final UUID me = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    private DispatchAttempt attempt(UUID incident, DispatchResponseType response, int secondsAgo) {
        return DispatchAttempt.builder().incidentId(incident).responderId(me).attemptNumber(1)
                .sentAt(Instant.now().minusSeconds(secondsAgo)).response(response).build();
    }

    private Incident incident(UUID id, IncidentStatus status, UUID assigned, Duration age) {
        return Incident.builder().id(id).crashEventId(UUID.randomUUID()).status(status)
                .assignedResponderId(assigned).driverName("Ali").gpsLat(31.5).gpsLng(74.3).priorityScore(4)
                .createdAt(Instant.now().minus(age)).build();
    }

    @Test
    @DisplayName("history — one row per incident (newest answer), and who can still accept it again")
    void history_rows() {
        UUID open = UUID.randomUUID();
        UUID taken = UUID.randomUUID();
        UUID mine = UUID.randomUUID();
        UUID old = UUID.randomUUID();
        when(attemptRepository.findTop100ByResponderIdOrderBySentAtDesc(me)).thenReturn(List.of(
                attempt(open, DispatchResponseType.REJECTED, 10),
                attempt(taken, DispatchResponseType.TIMED_OUT, 20),
                attempt(mine, DispatchResponseType.ACCEPTED, 30),
                attempt(old, DispatchResponseType.REJECTED, 40),
                attempt(open, DispatchResponseType.TIMED_OUT, 50)));   // older answer, same incident
        when(incidentRepository.findAllById(any())).thenReturn(List.of(
                incident(open, IncidentStatus.DISPATCHED, other, Duration.ofMinutes(2)),
                incident(taken, IncidentStatus.ACCEPTED, other, Duration.ofMinutes(3)),
                incident(mine, IncidentStatus.RESOLVED, me, Duration.ofMinutes(20)),
                incident(old, IncidentStatus.DISPATCHING, null, Duration.ofMinutes(45))));

        List<ResponderHistoryItem> rows = historyService.history(me);

        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).incidentId()).isEqualTo(open.toString());
        assertThat(rows.get(0).myResponse()).isEqualTo("REJECTED");
        assertThat(rows.get(0).canAcceptAgain()).isTrue();
        assertThat(rows.get(1).canAcceptAgain()).isFalse();   // somebody accepted
        assertThat(rows.get(2).assignedToMe()).isTrue();
        assertThat(rows.get(2).canAcceptAgain()).isFalse();
        assertThat(rows.get(3).canAcceptAgain()).isFalse();   // older than 30 minutes
    }

    @Test
    @DisplayName("history — nothing offered yet: empty list")
    void history_empty() {
        when(attemptRepository.findTop100ByResponderIdOrderBySentAtDesc(me)).thenReturn(List.of());

        assertThat(historyService.history(me)).isEmpty();
        verifyNoInteractions(incidentRepository);
    }
}
