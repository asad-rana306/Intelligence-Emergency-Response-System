package com.iers.auth.service;

import com.iers.auth.dto.request.UpdateDutyStatusRequest;
import com.iers.auth.dto.request.UpdateLocationRequest;
import com.iers.auth.dto.response.AvailableResponderResponse;
import com.iers.auth.dto.response.ResponderProfileResponse;
import com.iers.auth.entity.ResponderProfile;
import com.iers.auth.entity.User;
import com.iers.auth.entity.enums.DutyStatus;
import com.iers.auth.entity.enums.Role;
import com.iers.auth.exception.ResourceNotFoundException;
import com.iers.auth.repository.ResponderProfileRepository;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResponderServiceTest {

    @Mock private ResponderProfileRepository profileRepository;

    @InjectMocks private ResponderService service;

    private User testUser;
    private ResponderProfile testProfile;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
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

    @Test
    @DisplayName("updateDutyStatus — success")
    void updateDutyStatus_success() {
        UpdateDutyStatusRequest request = UpdateDutyStatusRequest.builder()
                .status(DutyStatus.ON_DUTY).build();

        when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResponderProfileResponse response = service.updateDutyStatus(userId, request);

        assertThat(response.getDutyStatus()).isEqualTo(DutyStatus.ON_DUTY);
    }

    @Test
    @DisplayName("updateDutyStatus — throws when profile not found")
    void updateDutyStatus_notFound() {
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateDutyStatus(userId,
                UpdateDutyStatusRequest.builder().status(DutyStatus.ON_DUTY).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("updateLocation — success")
    void updateLocation_success() {
        UpdateLocationRequest request = UpdateLocationRequest.builder()
                .latitude(34.0522).longitude(-118.2437).build();

        when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResponderProfileResponse response = service.updateLocation(userId, request);

        assertThat(response.getCurrentLat()).isEqualTo(34.0522);
        assertThat(response.getCurrentLng()).isEqualTo(-118.2437);
    }

    @Test
    @DisplayName("updateLocation — throws when profile not found")
    void updateLocation_notFound() {
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateLocation(userId,
                UpdateLocationRequest.builder().latitude(0.0).longitude(0.0).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getAvailableResponders — returns on-duty responders with GPS")
    void getAvailableResponders_success() {
        testProfile.setDutyStatus(DutyStatus.ON_DUTY);
        when(profileRepository.findAllByDutyStatusWithUser(DutyStatus.ON_DUTY))
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
        when(profileRepository.findAllByDutyStatusWithUser(DutyStatus.ON_DUTY))
                .thenReturn(List.of());

        List<AvailableResponderResponse> result = service.getAvailableResponders();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("updateStatusByUserId — internal update success")
    void updateStatusByUserId_success() {
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));
        when(profileRepository.save(any())).thenReturn(testProfile);

        service.updateStatusByUserId(userId, DutyStatus.ON_MISSION);

        assertThat(testProfile.getDutyStatus()).isEqualTo(DutyStatus.ON_MISSION);
        verify(profileRepository).save(testProfile);
    }

    @Test
    @DisplayName("updateStatusByUserId — throws when profile not found")
    void updateStatusByUserId_notFound() {
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatusByUserId(userId, DutyStatus.ON_MISSION))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
