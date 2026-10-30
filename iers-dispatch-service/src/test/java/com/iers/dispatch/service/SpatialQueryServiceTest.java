package com.iers.dispatch.service;

import com.iers.dispatch.dto.feign.AvailableResponderDto;
import com.iers.dispatch.entity.ResponderLocation;
import com.iers.dispatch.feign.AuthServiceClient;
import com.iers.dispatch.repository.ResponderLocationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpatialQueryServiceTest {

    @Mock private ResponderLocationRepository locationRepository;
    @Mock private AuthServiceClient authServiceClient;
    @InjectMocks private SpatialQueryService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();

    private AvailableResponderDto dto(UUID user) {
        return AvailableResponderDto.builder()
                .responderId(profileId.toString())
                .userId(user != null ? user.toString() : null)
                .fullName("Bilal").phone("+923009876543").vehicleId("AMB-12")
                .latitude(31.52).longitude(74.35).build();
    }

    private ResponderLocation row(UUID id, String duty) {
        return ResponderLocation.builder().responderId(id).dutyStatus(duty).build();
    }

    // ── search ──

    @Test
    @DisplayName("findNearestResponder — no exclusions uses the simple query")
    void nearest_noExclusions() {
        ResponderLocation loc = row(userId, "ON_DUTY");
        when(locationRepository.findNearestRespondersNoExclusion(31.5, 74.3, 1)).thenReturn(List.of(loc));

        assertThat(service.findNearestResponder(31.5, 74.3, null)).isSameAs(loc);
        assertThat(service.findNearestResponder(31.5, 74.3, List.of())).isSameAs(loc);
    }

    @Test
    @DisplayName("findNearestResponder — with exclusions uses the exclusion query")
    void nearest_withExclusions() {
        ResponderLocation loc = row(userId, "ON_DUTY");
        List<UUID> excluded = List.of(UUID.randomUUID());
        when(locationRepository.findNearestResponders(31.5, 74.3, excluded, 1)).thenReturn(List.of(loc));

        assertThat(service.findNearestResponder(31.5, 74.3, excluded)).isSameAs(loc);
    }

    @Test
    @DisplayName("findNearestResponder — nobody found gives null")
    void nearest_none() {
        when(locationRepository.findNearestRespondersNoExclusion(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of());
        assertThat(service.findNearestResponder(1, 2, null)).isNull();
    }

    // ── sync (PHASE 3: keyed by USER id) ──

    @Test
    @DisplayName("sync — a new responder is stored under the USER id, with phone")
    void sync_newResponder() {
        when(authServiceClient.getAvailableResponders()).thenReturn(List.of(dto(userId)));
        when(locationRepository.findByResponderId(userId)).thenReturn(Optional.empty());
        when(locationRepository.findAll()).thenReturn(List.of());

        service.syncResponderLocations();

        ArgumentCaptor<ResponderLocation> saved = ArgumentCaptor.forClass(ResponderLocation.class);
        verify(locationRepository).save(saved.capture());
        assertThat(saved.getValue().getResponderId()).isEqualTo(userId);
        assertThat(saved.getValue().getUserName()).isEqualTo("Bilal");
        assertThat(saved.getValue().getPhone()).isEqualTo("+923009876543");
        assertThat(saved.getValue().getVehicleId()).isEqualTo("AMB-12");
        assertThat(saved.getValue().getDutyStatus()).isEqualTo("ON_DUTY");
        assertThat(saved.getValue().getLastUpdated()).isNotNull();
    }

    @Test
    @DisplayName("sync — an existing responder is updated in place")
    void sync_existingResponder() {
        ResponderLocation existing = row(userId, "ON_MISSION");
        when(authServiceClient.getAvailableResponders()).thenReturn(List.of(dto(userId)));
        when(locationRepository.findByResponderId(userId)).thenReturn(Optional.of(existing));
        when(locationRepository.findAll()).thenReturn(List.of(existing));

        service.syncResponderLocations();

        assertThat(existing.getDutyStatus()).isEqualTo("ON_DUTY");
        assertThat(existing.getLatitude()).isEqualTo(31.52);
    }

    @Test
    @DisplayName("sync — someone ON_DUTY here but missing from Auth's list becomes OFF_DUTY")
    void sync_marksMissingOffDuty() {
        ResponderLocation stale = row(UUID.randomUUID(), "ON_DUTY");   // e.g. an old profile-id row
        ResponderLocation onMission = row(UUID.randomUUID(), "ON_MISSION");
        ResponderLocation current = row(userId, "ON_DUTY");
        when(authServiceClient.getAvailableResponders()).thenReturn(List.of(dto(userId)));
        when(locationRepository.findByResponderId(userId)).thenReturn(Optional.of(current));
        when(locationRepository.findAll()).thenReturn(List.of(stale, onMission, current));

        service.syncResponderLocations();

        assertThat(stale.getDutyStatus()).isEqualTo("OFF_DUTY");
        assertThat(onMission.getDutyStatus()).isEqualTo("ON_MISSION"); // busy responders stay as they are
        assertThat(current.getDutyStatus()).isEqualTo("ON_DUTY");
    }

    @Test
    @DisplayName("sync — an empty list (Auth down also looks empty) changes nobody")
    void sync_emptyListIgnored() {
        when(authServiceClient.getAvailableResponders()).thenReturn(new ArrayList<>());

        service.syncResponderLocations();

        verify(locationRepository, never()).findAll();
        verify(locationRepository, never()).save(any());
    }

    @Test
    @DisplayName("sync — an entry without a user id is skipped")
    void sync_skipsMissingUserId() {
        when(authServiceClient.getAvailableResponders()).thenReturn(List.of(dto(null)));

        service.syncResponderLocations();

        verify(locationRepository, never()).save(any());
    }

    @Test
    @DisplayName("sync — an Auth failure is logged, not thrown")
    void sync_authFails() {
        when(authServiceClient.getAvailableResponders()).thenThrow(new RuntimeException("auth down"));
        service.syncResponderLocations(); // must not throw
        verifyNoInteractions(locationRepository);
    }

    // ── duty flags ──

    @Test
    @DisplayName("markResponderOnMission / markResponderOnDuty update the local row")
    void dutyFlags() {
        ResponderLocation loc = row(userId, "ON_DUTY");
        when(locationRepository.findByResponderId(userId)).thenReturn(Optional.of(loc));

        service.markResponderOnMission(userId);
        assertThat(loc.getDutyStatus()).isEqualTo("ON_MISSION");

        service.markResponderOnDuty(userId);
        assertThat(loc.getDutyStatus()).isEqualTo("ON_DUTY");
    }

    @Test
    @DisplayName("duty flags — unknown responder is ignored")
    void dutyFlags_unknown() {
        when(locationRepository.findByResponderId(userId)).thenReturn(Optional.empty());
        service.markResponderOnMission(userId);
        service.markResponderOnDuty(userId);
        verify(locationRepository, never()).save(any());
    }
}
