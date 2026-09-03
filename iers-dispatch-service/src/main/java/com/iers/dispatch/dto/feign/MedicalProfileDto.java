package com.iers.dispatch.dto.feign;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class MedicalProfileDto {
    private String id;
    private String userId;
    private String bloodType;
    private String allergies;
    private String medications;
    private String chronicConditions;
    private String emergencyNotes;
}
