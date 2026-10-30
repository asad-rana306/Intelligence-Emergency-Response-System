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

    /** Optional since Phase 2: the server stamps the time when this is missing. */
    private Long timestamp;
}
