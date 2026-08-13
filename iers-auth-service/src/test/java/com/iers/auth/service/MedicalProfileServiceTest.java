package com.iers.auth.service;

import com.iers.auth.dto.request.MedicalProfileRequest;
import com.iers.auth.dto.response.MedicalProfileResponse;
import com.iers.auth.entity.MedicalProfile;
import com.iers.auth.entity.User;
import com.iers.auth.entity.enums.Role;
import com.iers.auth.exception.DuplicateResourceException;
import com.iers.auth.exception.ResourceNotFoundException;
import com.iers.auth.repository.MedicalProfileRepository;
import com.iers.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicalProfileServiceTest {

    @Mock private MedicalProfileRepository profileRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private MedicalProfileService service;

    private User testUser;
    private MedicalProfile testProfile;
    private final UUID userId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(userId).email("driver@test.com")
                .fullName("Test Driver").role(Role.DRIVER).build();

        testProfile = MedicalProfile.builder()
                .id(profileId).user(testUser)
                .bloodType("O+").allergies("Penicillin")
                .medications("None").chronicConditions("Asthma")
                .emergencyNotes("Carries inhaler").build();
    }

    @Test
    @DisplayName("createProfile — success")
    void createProfile_success() {
        MedicalProfileRequest request = MedicalProfileRequest.builder()
                .bloodType("O+").allergies("Penicillin").build();

        when(profileRepository.existsByUserId(userId)).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(profileRepository.save(any())).thenReturn(testProfile);

        MedicalProfileResponse response = service.createProfile(userId, request);

        assertThat(response.getBloodType()).isEqualTo("O+");
        assertThat(response.getUserId()).isEqualTo(userId.toString());
    }

    @Test
    @DisplayName("createProfile — throws when profile already exists")
    void createProfile_duplicate() {
        when(profileRepository.existsByUserId(userId)).thenReturn(true);

        assertThatThrownBy(() -> service.createProfile(userId, new MedicalProfileRequest()))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("createProfile — throws when user not found")
    void createProfile_userNotFound() {
        when(profileRepository.existsByUserId(userId)).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createProfile(userId, new MedicalProfileRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getProfileByUserId — success")
    void getProfile_success() {
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));

        MedicalProfileResponse response = service.getProfileByUserId(userId);

        assertThat(response.getBloodType()).isEqualTo("O+");
        assertThat(response.getAllergies()).isEqualTo("Penicillin");
    }

    @Test
    @DisplayName("getProfileByUserId — throws when not found")
    void getProfile_notFound() {
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProfileByUserId(userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("updateProfile — partial update only modifies provided fields")
    void updateProfile_partialUpdate() {
        MedicalProfileRequest request = MedicalProfileRequest.builder()
                .bloodType("A-").build(); // Only updating blood type

        when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MedicalProfileResponse response = service.updateProfile(userId, request);

        assertThat(response.getBloodType()).isEqualTo("A-");
        assertThat(response.getAllergies()).isEqualTo("Penicillin"); // Unchanged
    }

    @Test
    @DisplayName("updateProfile — throws when profile not found")
    void updateProfile_notFound() {
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProfile(userId, new MedicalProfileRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
