package com.iers.iot.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class DevicePairRequest {
    @NotBlank private String deviceId;
    @NotBlank private String driverName;
    @NotBlank private String driverPhone;
}
