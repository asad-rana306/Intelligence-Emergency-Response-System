package com.iers.dispatch.dto.request;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class GpsUpdateMessage {
    private String incidentId;
    private String responderId;
    private Double latitude;
    private Double longitude;
    private Double heading;
    private Double speed;
    private String estimatedEta;
}
