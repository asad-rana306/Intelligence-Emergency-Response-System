package com.iers.iot.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.Map;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CrashPayloadRequest {
    @NotNull @JsonProperty("gForce")
    private Double gForce;

    @NotNull @JsonProperty("rolloverAngle")
    private Double rolloverAngle;

    @NotNull
    private Double speed;

    @NotNull @JsonProperty("gpsLat")
    private Double gpsLat;

    @NotNull @JsonProperty("gpsLng")
    private Double gpsLng;

    @NotNull
    private Long timestamp;

    @JsonProperty("sensorData")
    private Map<String, Object> sensorData;
}

