package com.iers.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class OtpVerifyRequest {
    @NotBlank
    private String phone;

    @NotBlank @Pattern(regexp = "\\d{4,9}", message = "must be the digits of the code")
    private String code;
}
