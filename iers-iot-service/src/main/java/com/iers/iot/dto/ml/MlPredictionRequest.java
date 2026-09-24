package com.iers.iot.dto.ml;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import java.util.Map;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class MlPredictionRequest {
    @JsonProperty("gForce")
    private Double gForce;

    @JsonProperty("rolloverAngle")
    private Double rolloverAngle;

    private Double speed;

    @JsonProperty("sensorData")
    private Map<String, Object> sensorData;
}