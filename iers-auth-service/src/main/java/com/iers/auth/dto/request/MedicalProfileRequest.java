package com.iers.auth.dto.request;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class MedicalProfileRequest {
    private String bloodType;
    private String allergies;
    private String medications;
    private String chronicConditions;
    private String emergencyNotes;
}
