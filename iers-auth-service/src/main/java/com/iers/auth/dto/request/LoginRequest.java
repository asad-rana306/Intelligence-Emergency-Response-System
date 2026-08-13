package com.iers.auth.dto.request;

import jakarta.validation.constraints.*;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class LoginRequest {
    @NotBlank @Email
    private String email;

    @NotBlank
    private String password;

    /** Optional: register the device for push notifications on login */
    private String deviceId;
}
