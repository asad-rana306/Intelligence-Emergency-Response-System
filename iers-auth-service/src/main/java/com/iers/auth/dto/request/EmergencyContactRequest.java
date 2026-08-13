package com.iers.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class EmergencyContactRequest {
    @NotBlank
    private String contactName;

    @NotBlank
    private String phone;

    private String relationship;
}
