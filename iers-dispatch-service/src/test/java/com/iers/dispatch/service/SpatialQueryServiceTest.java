package com.iers.dispatch.service;

import com.iers.dispatch.dto.feign.AvailableResponderDto;
import com.iers.dispatch.entity.ResponderLocation;
import com.iers.dispatch.exception.AuthUnavailableException;
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

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<ResponderLocation>> savedCaptor() {
        return ArgumentCaptor.forClass((Class<List<ResponderLocation>>) (Class<?>) List.class);
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
        when(locationRepository.findByDutyStatus("ON_DUTY")).thenReturn(List.of());

        service.syncResponderLocations();

        ArgumentCaptor<List<ResponderLocation>> saved = savedCaptor();
        verify(locationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        ResponderLocation loc = saved.getValue().get(0);
        assertThat(loc.getResponderId()).isEqualTo(userId);
        assertThat(loc.getUserName()).isEqualTo("Bilal");
        assertThat(loc.getPhone()).isEqualTo("+923009876543");
        assertThat(loc.getVehicleId()).isEqualTo("AMB-12");
        assertThat(loc.getDutyStatus()).isEqualTo("ON_DUTY");
        assertThat(loc.getLastUpdated()).isNotNull();
    }

    @Test
    @DisplayName("sync — an existing responder is updated in place")
    void sync_existingResponder() {
        ResponderLocation existing = row(userId, "ON_MISSION");
        when(authServiceClient.getAvailableResponders()).thenReturn(List.of(dto(userId)));
        when(locationRepository.findByResponderId(userId)).thenReturn(Optional.of(existing));
        when(locationRepository.findByDutyStatus("ON_DUTY")).thenReturn(List.of());

        service.syncResponderLocations();

        assertThat(existing.getDutyStatus()).isEqualTo("ON_DUTY");
        assertThat(existing.getLatitude()).isEqualTo(31.52);
    }

    @Test
    @DisplayName("sync — someone ON_DUTY here but missing from Auth's list becomes OFF_DUTY")
    void sync_marksMissingOffDuty() {
        ResponderLocation stale = row(UUID.randomUUID(), "ON_DUTY");   // shift over / old row
        ResponderLocation current = row(userId, "ON_DUTY");
        when(authServiceClient.getAvailableResponders()).thenReturn(List.of(dto(userId)));
        when(locationRepository.findByResponderId(userId)).thenReturn(Optional.of(current));
        // only ON_DUTY rows are looked at, so a busy (ON_MISSION) responder is never touched
        when(locationRepository.findByDutyStatus("ON_DUTY")).thenReturn(List.of(stale, current));

        service.syncResponderLocations();

        assertThat(stale.getDutyStatus()).isEqualTo("OFF_DUTY");
        assertThat(current.getDutyStatus()).isEqualTo("ON_DUTY");
        verify(locationRepository).saveAll(argThat((List<ResponderLocation> l) -> l.contains(stale)));
    }

    @Test
    @DisplayName("sync — Auth ANSWERS with an empty list: the last responder going off duty is removed too")
    void sync_emptyAnswerMarksEveryoneOff() {
        ResponderLocation last = row(userId, "ON_DUTY");
        when(authServiceClient.getAvailableResponders()).thenReturn(new ArrayList<>());
        when(locationRepository.findByDutyStatus("ON_DUTY")).thenReturn(List.of(last));

        service.syncResponderLocations();

        assertThat(last.getDutyStatus()).isEqualTo("OFF_DUTY");
        verify(locationRepository).saveAll(List.of(last));
    }

    @Test
    @DisplayName("sync — Auth does NOT answer (fallback throws): nobody is changed")
    void sync_authUnavailableChangesNobody() {
        when(authServiceClient.getAvailableResponders())
                .thenThrow(new AuthUnavailableException("down", new RuntimeException("x")));

        service.syncResponderLocations();

        verifyNoInteractions(locationRepository);
    }

    @Test
    @DisplayName("sync — an entry without a user id is skipped")
    void sync_skipsMissingUserId() {
        when(authServiceClient.getAvailableResponders()).thenReturn(List.of(dto(null)));
        when(locationRepository.findByDutyStatus("ON_DUTY")).thenReturn(List.of());

        service.syncResponderLocations();

        verify(locationRepository, never()).saveAll(any());
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
    @DisplayName("markResponderOffDuty — an ON_DUTY responder leaves the pool at once")
    void offDuty_removesFromPool() {
        ResponderLocation loc = row(userId, "ON_DUTY");
        when(locationRepository.findByResponderId(userId)).thenReturn(Optional.of(loc));

        service.markResponderOffDuty(userId);

        assertThat(loc.getDutyStatus()).isEqualTo("OFF_DUTY");
        verify(locationRepository).save(loc);
    }

    @Test
    @DisplayName("markResponderOffDuty — a responder ON_MISSION is left alone, an unknown one is ignored")
    void offDuty_busyOrUnknown() {
        ResponderLocation busy = row(userId, "ON_MISSION");
        when(locationRepository.findByResponderId(userId)).thenReturn(Optional.of(busy));
        service.markResponderOffDuty(userId);
        assertThat(busy.getDutyStatus()).isEqualTo("ON_MISSION");

        UUID other = UUID.randomUUID();
        when(locationRepository.findByResponderId(other)).thenReturn(Optional.empty());
        service.markResponderOffDuty(other);

        verify(locationRepository, never()).save(any());
    }

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
