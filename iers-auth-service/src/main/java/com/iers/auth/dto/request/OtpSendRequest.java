package com.iers.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class OtpSendRequest {
    @NotBlank
    private String phone;
}
