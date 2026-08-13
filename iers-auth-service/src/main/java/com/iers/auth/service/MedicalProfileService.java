package com.iers.auth.service;

import com.iers.auth.dto.request.MedicalProfileRequest;
import com.iers.auth.dto.response.MedicalProfileResponse;
import com.iers.auth.entity.MedicalProfile;
import com.iers.auth.entity.User;
import com.iers.auth.exception.DuplicateResourceException;
import com.iers.auth.exception.ResourceNotFoundException;
import com.iers.auth.repository.MedicalProfileRepository;
import com.iers.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MedicalProfileService {

    private final MedicalProfileRepository medicalProfileRepository;
    private final UserRepository userRepository;

    @Transactional
    public MedicalProfileResponse createProfile(UUID userId, MedicalProfileRequest request) {
        if (medicalProfileRepository.existsByUserId(userId)) {
            throw new DuplicateResourceException("Medical profile already exists for user: " + userId);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        MedicalProfile profile = MedicalProfile.builder()
                .user(user)
                .bloodType(request.getBloodType())
                .allergies(request.getAllergies())
                .medications(request.getMedications())
                .chronicConditions(request.getChronicConditions())
                .emergencyNotes(request.getEmergencyNotes())
                .build();

        profile = medicalProfileRepository.save(profile);
        return MedicalProfileResponse.from(profile);
    }

    public MedicalProfileResponse getProfileByUserId(UUID userId) {
        MedicalProfile profile = medicalProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Medical profile not found for user: " + userId));
        return MedicalProfileResponse.from(profile);
    }

    @Transactional
    public MedicalProfileResponse updateProfile(UUID userId, MedicalProfileRequest request) {
        MedicalProfile profile = medicalProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Medical profile not found for user: " + userId));

        if (request.getBloodType() != null) profile.setBloodType(request.getBloodType());
        if (request.getAllergies() != null) profile.setAllergies(request.getAllergies());
        if (request.getMedications() != null) profile.setMedications(request.getMedications());
        if (request.getChronicConditions() != null) profile.setChronicConditions(request.getChronicConditions());
        if (request.getEmergencyNotes() != null) profile.setEmergencyNotes(request.getEmergencyNotes());

        profile = medicalProfileRepository.save(profile);
        return MedicalProfileResponse.from(profile);
    }
}
