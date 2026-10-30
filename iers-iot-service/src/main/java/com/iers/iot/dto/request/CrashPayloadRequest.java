package com.iers.iot.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.Map;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CrashPayloadRequest {
    @NotNull private Double gForce;
    @NotNull private Double rolloverAngle;
    @NotNull private Double speed;
    @NotNull private Double gpsLat;
    @NotNull private Double gpsLng;

    /** Optional since Phase 2: the ESP32 has no clock, so the server stamps the time when this is missing. */
    private Long timestamp;

    private Map<String, Object> sensorData;
}
