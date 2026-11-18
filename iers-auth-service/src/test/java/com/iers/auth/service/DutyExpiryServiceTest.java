package com.iers.auth.service;

import com.iers.auth.config.DutyProperties;
import com.iers.auth.entity.ResponderProfile;
import com.iers.auth.entity.User;
import com.iers.auth.entity.enums.DutyStatus;
import com.iers.auth.event.DutyEvent;
import com.iers.auth.event.DutyEventPublisher;
import com.iers.auth.repository.ResponderProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DutyExpiryServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant STALE_BEFORE = NOW.minus(Duration.ofMinutes(2));

    @Mock private ResponderProfileRepository repository;
    @Mock private DutyEventPublisher publisher;

    private final DutyProperties props = new DutyProperties(
            Duration.ofHours(1), Duration.ofMinutes(2), Duration.ofSeconds(25),
            new DutyProperties.Events(false, "duty-events"));

    private DutyExpiryService service;

    @BeforeEach
    void setUp() {
        service = new DutyExpiryService(repository, props, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ResponderProfile onDuty(Instant expiresAt, Instant lastHeartbeat) {
        return ResponderProfile.builder()
                .id(UUID.randomUUID())
                .user(User.builder().id(UUID.randomUUID()).build())
                .dutyStatus(DutyStatus.ON_DUTY)
                .dutyExpiresAt(expiresAt)
                .lastHeartbeatAt(lastHeartbeat)
                .build();
    }

    private void due(List<ResponderProfile> profiles) {
        when(repository.findDue(eq(DutyStatus.ON_DUTY), eq(NOW), eq(STALE_BEFORE), any(Pageable.class)))
                .thenReturn(profiles);
    }

    private void winsTheRace(ResponderProfile p) {
        when(repository.expireIfStillDue(p.getId(), NOW, STALE_BEFORE, DutyStatus.ON_DUTY, DutyStatus.OFF_DUTY))
                .thenReturn(1);
    }

    @Test
    @DisplayName("nothing due — nothing is changed")
    void nothingDue() {
        due(List.of());

        assertThat(service.expireDue()).isZero();

        verify(repository, never()).expireIfStillDue(any(), any(), any(), any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("shift over — OFF duty and DUTY_EXPIRED / MAX_DURATION is published")
    void shiftOver() {
        ResponderProfile p = onDuty(NOW.minusSeconds(5), NOW.minusSeconds(10));
        due(List.of(p));
        winsTheRace(p);

        assertThat(service.expireDue()).isEqualTo(1);

        ArgumentCaptor<DutyEvent> event = ArgumentCaptor.forClass(DutyEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo(DutyEvent.DUTY_EXPIRED);
        assertThat(event.getValue().reason()).isEqualTo(DutyEvent.MAX_DURATION);
        assertThat(event.getValue().status()).isEqualTo("OFF_DUTY");
        assertThat(event.getValue().userId()).isEqualTo(p.getUser().getId().toString());
    }

    @Test
    @DisplayName("phone went silent — reason is HEARTBEAT_LOST")
    void heartbeatLost() {
        ResponderProfile p = onDuty(NOW.plus(Duration.ofMinutes(30)), NOW.minus(Duration.ofMinutes(5)));
        due(List.of(p));
        winsTheRace(p);

        service.expireDue();

        ArgumentCaptor<DutyEvent> event = ArgumentCaptor.forClass(DutyEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().reason()).isEqualTo(DutyEvent.HEARTBEAT_LOST);
    }

    @Test
    @DisplayName("old ON_DUTY row without timestamps is cleaned up (reason HEARTBEAT_LOST)")
    void legacyRowWithoutTimestamps() {
        ResponderProfile p = onDuty(null, null);
        due(List.of(p));
        winsTheRace(p);

        assertThat(service.expireDue()).isEqualTo(1);

        ArgumentCaptor<DutyEvent> event = ArgumentCaptor.forClass(DutyEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().reason()).isEqualTo(DutyEvent.HEARTBEAT_LOST);
    }

    @Test
    @DisplayName("another instance (or a heartbeat) was faster — no event, not counted")
    void lostTheRace() {
        ResponderProfile p = onDuty(NOW.minusSeconds(1), NOW);
        due(List.of(p));
        when(repository.expireIfStillDue(p.getId(), NOW, STALE_BEFORE, DutyStatus.ON_DUTY, DutyStatus.OFF_DUTY))
                .thenReturn(0);

        assertThat(service.expireDue()).isZero();

        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("a full batch makes the sweep ask again")
    void fullBatchContinues() {
        List<ResponderProfile> first = new ArrayList<>();
        for (int i = 0; i < DutyExpiryService.BATCH_SIZE; i++) {
            first.add(onDuty(NOW.minusSeconds(1), NOW));
        }
        when(repository.findDue(eq(DutyStatus.ON_DUTY), eq(NOW), eq(STALE_BEFORE), any(Pageable.class)))
                .thenReturn(first, List.of());
        when(repository.expireIfStillDue(any(), eq(NOW), eq(STALE_BEFORE), any(), any())).thenReturn(1);

        assertThat(service.expireDue()).isEqualTo(DutyExpiryService.BATCH_SIZE);

        verify(repository, times(2)).findDue(any(), any(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("one sweep never loops forever")
    void sweepIsBounded() {
        List<ResponderProfile> full = new ArrayList<>();
        for (int i = 0; i < DutyExpiryService.BATCH_SIZE; i++) {
            full.add(onDuty(NOW.minusSeconds(1), NOW));
        }
        due(full); // the repository keeps returning a full batch
        when(repository.expireIfStillDue(any(), eq(NOW), eq(STALE_BEFORE), any(), any())).thenReturn(1);

        service.expireDue();

        verify(repository, times(DutyExpiryService.MAX_BATCHES))
                .findDue(any(), any(), any(), any(Pageable.class));
    }
}
