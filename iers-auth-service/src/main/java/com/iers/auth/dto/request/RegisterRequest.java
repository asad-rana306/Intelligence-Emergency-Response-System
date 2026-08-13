package com.iers.auth.dto.request;

import com.iers.auth.entity.enums.Role;
import jakarta.validation.constraints.*;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RegisterRequest {
    @NotBlank @Email
    private String email;

    @NotBlank @Size(min = 8, max = 100)
    private String password;

    @NotBlank
    private String fullName;

    @NotBlank
    private String phone;

    @NotNull
    private Role role;

    /** Only required for RESPONDER role */
    private String vehicleId;
}
