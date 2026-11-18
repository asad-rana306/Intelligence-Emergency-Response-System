package com.iers.auth.service;

import com.iers.auth.config.DutyProperties;
import com.iers.auth.dto.request.UpdateDutyStatusRequest;
import com.iers.auth.dto.request.UpdateLocationRequest;
import com.iers.auth.dto.response.AvailableResponderResponse;
import com.iers.auth.dto.response.ResponderProfileResponse;
import com.iers.auth.entity.ResponderProfile;
import com.iers.auth.entity.User;
import com.iers.auth.entity.enums.DutyStatus;
import com.iers.auth.entity.enums.Role;
import com.iers.auth.event.DutyEvent;
import com.iers.auth.event.DutyEventPublisher;
import com.iers.auth.exception.ResourceNotFoundException;
import com.iers.auth.repository.ResponderProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResponderServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Mock private ResponderProfileRepository profileRepository;
    @Mock private DutyEventPublisher eventPublisher;

    private final DutyProperties props = new DutyProperties(
            Duration.ofHours(1), Duration.ofMinutes(2), Duration.ofSeconds(25),
            new DutyProperties.Events(false, "duty-events"));

    private ResponderService service;
    private User testUser;
    private ResponderProfile testProfile;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ResponderService(profileRepository, props, eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC));

        testUser = User.builder()
                .id(userId).email("resp@test.com")
                .fullName("Test Responder").phone("+1234567890")
                .role(Role.RESPONDER).build();

        testProfile = ResponderProfile.builder()
                .id(UUID.randomUUID()).user(testUser)
                .dutyStatus(DutyStatus.OFF_DUTY)
                .vehicleId("AMB-001")
                .currentLat(37.7749).currentLng(-122.4194).build();
    }

    private void profileExists() {
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));
    }

    private void saveReturnsArgument() {
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ══════════════════ STATUS ══════════════════

    @Test
    @DisplayName("updateDutyStatus — ON_DUTY starts a one hour shift")
    void updateDutyStatus_onDuty_startsShift() {
        profileExists();
        saveReturnsArgument();

        ResponderProfileResponse response = service.updateDutyStatus(userId,
                UpdateDutyStatusRequest.builder().status(DutyStatus.ON_DUTY).build());

        assertThat(response.getDutyStatus()).isEqualTo(DutyStatus.ON_DUTY);
        assertThat(response.getDutyStartedAt()).isEqualTo(NOW);
        assertThat(response.getDutyExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(response.getSecondsRemaining()).isEqualTo(3600L);
        assertThat(response.getServerTime()).isEqualTo(NOW);
        assertThat(testProfile.getLastHeartbeatAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("updateDutyStatus — a change publishes DUTY_CHANGED")
    void updateDutyStatus_publishesEvent() {
        profileExists();
        saveReturnsArgument();

        service.updateDutyStatus(userId, UpdateDutyStatusRequest.builder().status(DutyStatus.ON_DUTY).build());

        ArgumentCaptor<DutyEvent> event = ArgumentCaptor.forClass(DutyEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo(DutyEvent.DUTY_CHANGED);
        assertThat(event.getValue().userId()).isEqualTo(userId.toString());
        assertThat(event.getValue().status()).isEqualTo("ON_DUTY");
    }

    @Test
    @DisplayName("updateDutyStatus — ON_DUTY again restarts the shift (\"Go on duty again\"), no new event")
    void updateDutyStatus_onDutyAgain_restartsShift() {
        testProfile.setDutyStatus(DutyStatus.ON_DUTY);
        testProfile.setDutyStartedAt(NOW.minus(Duration.ofMinutes(50)));
        testProfile.setDutyExpiresAt(NOW.plus(Duration.ofMinutes(10)));
        profileExists();
        saveReturnsArgument();

        ResponderProfileResponse response = service.updateDutyStatus(userId,
                UpdateDutyStatusRequest.builder().status(DutyStatus.ON_DUTY).build());

        assertThat(response.getDutyExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("updateDutyStatus — OFF_DUTY clears the shift")
    void updateDutyStatus_offDuty_clearsShift() {
        testProfile.setDutyStatus(DutyStatus.ON_DUTY);
        testProfile.setDutyStartedAt(NOW.minus(Duration.ofMinutes(5)));
        testProfile.setDutyExpiresAt(NOW.plus(Duration.ofMinutes(55)));
        testProfile.setLastHeartbeatAt(NOW);
        profileExists();
        saveReturnsArgument();

        ResponderProfileResponse response = service.updateDutyStatus(userId,
                UpdateDutyStatusRequest.builder().status(DutyStatus.OFF_DUTY).build());

        assertThat(response.getDutyStatus()).isEqualTo(DutyStatus.OFF_DUTY);
        assertThat(response.getDutyStartedAt()).isNull();
        assertThat(response.getDutyExpiresAt()).isNull();
        assertThat(response.getSecondsRemaining()).isNull();
        assertThat(testProfile.getLastHeartbeatAt()).isNull();
    }

    @Test
    @DisplayName("updateDutyStatus — throws when profile not found")
    void updateDutyStatus_notFound() {
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateDutyStatus(userId,
                UpdateDutyStatusRequest.builder().status(DutyStatus.ON_DUTY).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ══════════════════ LOCATION ══════════════════

    @Test
    @DisplayName("updateLocation — success")
    void updateLocation_success() {
        profileExists();
        saveReturnsArgument();

        ResponderProfileResponse response = service.updateLocation(userId,
                UpdateLocationRequest.builder().latitude(34.0522).longitude(-118.2437).build());

        assertThat(response.getCurrentLat()).isEqualTo(34.0522);
        assertThat(response.getCurrentLng()).isEqualTo(-118.2437);
    }

    @Test
    @DisplayName("updateLocation — counts as a heartbeat while ON_DUTY")
    void updateLocation_refreshesHeartbeatWhenOnDuty() {
        testProfile.setDutyStatus(DutyStatus.ON_DUTY);
        profileExists();
        saveReturnsArgument();

        service.updateLocation(userId, UpdateLocationRequest.builder().latitude(1.0).longitude(2.0).build());

        assertThat(testProfile.getLastHeartbeatAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("updateLocation — does not start a heartbeat while OFF_DUTY")
    void updateLocation_noHeartbeatWhenOffDuty() {
        profileExists();
        saveReturnsArgument();

        service.updateLocation(userId, UpdateLocationRequest.builder().latitude(1.0).longitude(2.0).build());

        assertThat(testProfile.getLastHeartbeatAt()).isNull();
    }

    @Test
    @DisplayName("updateLocation — throws when profile not found")
    void updateLocation_notFound() {
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateLocation(userId,
                UpdateLocationRequest.builder().latitude(0.0).longitude(0.0).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ══════════════════ HEARTBEAT / ME ══════════════════

    @Test
    @DisplayName("heartbeat — ON_DUTY: time is saved and the seconds left are returned")
    void heartbeat_onDuty() {
        testProfile.setDutyStatus(DutyStatus.ON_DUTY);
        testProfile.setDutyExpiresAt(NOW.plus(Duration.ofMinutes(20)));
        testProfile.setLastHeartbeatAt(NOW.minus(Duration.ofSeconds(30)));
        profileExists();
        saveReturnsArgument();

        ResponderProfileResponse response = service.heartbeat(userId);

        assertThat(testProfile.getLastHeartbeatAt()).isEqualTo(NOW);
        assertThat(response.getDutyStatus()).isEqualTo(DutyStatus.ON_DUTY);
        assertThat(response.getSecondsRemaining()).isEqualTo(1200L);
    }

    @Test
    @DisplayName("heartbeat — OFF_DUTY: nothing is written, the app learns it is OFF")
    void heartbeat_offDuty() {
        profileExists();

        ResponderProfileResponse response = service.heartbeat(userId);

        assertThat(response.getDutyStatus()).isEqualTo(DutyStatus.OFF_DUTY);
        verify(profileRepository, never()).save(any());
    }

    @Test
    @DisplayName("heartbeat — shift already over: seconds left is 0, never negative")
    void heartbeat_shiftOver_neverNegative() {
        testProfile.setDutyStatus(DutyStatus.ON_DUTY);
        testProfile.setDutyExpiresAt(NOW.minus(Duration.ofSeconds(10)));
        profileExists();
        saveReturnsArgument();

        ResponderProfileResponse response = service.heartbeat(userId);

        assertThat(response.getSecondsRemaining()).isZero();
    }

    @Test
    @DisplayName("heartbeat — throws when profile not found")
    void heartbeat_notFound() {
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.heartbeat(userId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getProfile — returns my status")
    void getProfile_success() {
        profileExists();

        assertThat(service.getProfile(userId).getVehicleId()).isEqualTo("AMB-001");
    }

    // ══════════════════ AVAILABLE ══════════════════

    @Test
    @DisplayName("getAvailableResponders — asks only for fresh, unexpired ON_DUTY responders")
    void getAvailableResponders_success() {
        testProfile.setDutyStatus(DutyStatus.ON_DUTY);
        when(profileRepository.findAvailable(DutyStatus.ON_DUTY, NOW, NOW.minus(Duration.ofMinutes(2))))
                .thenReturn(List.of(testProfile));

        List<AvailableResponderResponse> result = service.getAvailableResponders();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFullName()).isEqualTo("Test Responder");
        assertThat(result.get(0).getVehicleId()).isEqualTo("AMB-001");
        assertThat(result.get(0).getLatitude()).isEqualTo(37.7749);
    }

    @Test
    @DisplayName("getAvailableResponders — returns empty when none on duty")
    void getAvailableResponders_empty() {
        when(profileRepository.findAvailable(any(), any(), any())).thenReturn(List.of());

        assertThat(service.getAvailableResponders()).isEmpty();
    }

    // ══════════════════ INTERNAL (DISPATCH) ══════════════════

    @Test
    @DisplayName("updateStatusByUserId — ON_MISSION is applied")
    void updateStatusByUserId_onMission() {
        testProfile.setDutyStatus(DutyStatus.ON_DUTY);
        testProfile.setDutyExpiresAt(NOW.plus(Duration.ofMinutes(30)));
        profileExists();
        when(profileRepository.save(any())).thenReturn(testProfile);

        service.updateStatusByUserId(userId, DutyStatus.ON_MISSION);

        assertThat(testProfile.getDutyStatus()).isEqualTo(DutyStatus.ON_MISSION);
        assertThat(testProfile.getDutyExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30))); // kept
        verify(profileRepository).save(testProfile);
    }

    @Test
    @DisplayName("updateStatusByUserId — back to ON_DUTY after a mission starts a new shift")
    void updateStatusByUserId_backToOnDuty() {
        testProfile.setDutyStatus(DutyStatus.ON_MISSION);
        profileExists();
        when(profileRepository.save(any())).thenReturn(testProfile);

        service.updateStatusByUserId(userId, DutyStatus.ON_DUTY);

        assertThat(testProfile.getDutyStatus()).isEqualTo(DutyStatus.ON_DUTY);
        assertThat(testProfile.getDutyExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("updateStatusByUserId — a responder who is OFF_DUTY is NOT switched on by Dispatch")
    void updateStatusByUserId_doesNotSwitchOnOffDutyResponder() {
        profileExists(); // OFF_DUTY

        service.updateStatusByUserId(userId, DutyStatus.ON_DUTY);

        assertThat(testProfile.getDutyStatus()).isEqualTo(DutyStatus.OFF_DUTY);
        verify(profileRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateStatusByUserId — throws when profile not found")
    void updateStatusByUserId_notFound() {
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatusByUserId(userId, DutyStatus.ON_MISSION))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
