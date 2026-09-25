package com.iers.iot.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class HeartbeatRequest {
    @NotNull private Double speed;
    @NotNull private Double gpsLat;
    @NotNull private Double gpsLng;
    private Double fuelLevel;
    private String hardwareHealth;
    @NotNull private Long timestamp;
}
