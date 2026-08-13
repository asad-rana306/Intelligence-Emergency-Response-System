package com.iers.auth.dto.response;

import com.iers.auth.entity.MedicalProfile;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class MedicalProfileResponse {
    private String id;
    private String userId;
    private String bloodType;
    private String allergies;
    private String medications;
    private String chronicConditions;
    private String emergencyNotes;

    public static MedicalProfileResponse from(MedicalProfile mp) {
        return MedicalProfileResponse.builder()
                .id(mp.getId().toString())
                .userId(mp.getUser().getId().toString())
                .bloodType(mp.getBloodType())
                .allergies(mp.getAllergies())
                .medications(mp.getMedications())
                .chronicConditions(mp.getChronicConditions())
                .emergencyNotes(mp.getEmergencyNotes())
                .build();
    }
}
