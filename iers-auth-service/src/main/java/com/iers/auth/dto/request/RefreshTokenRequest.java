package com.iers.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RefreshTokenRequest {
    @NotBlank
    private String refreshToken;
}
