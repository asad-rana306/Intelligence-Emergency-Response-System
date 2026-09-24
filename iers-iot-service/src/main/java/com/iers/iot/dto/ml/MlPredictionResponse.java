package com.iers.iot.dto.ml;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class MlPredictionResponse {
    private boolean severe;
    private int priorityScore;
    private String severity;
}
