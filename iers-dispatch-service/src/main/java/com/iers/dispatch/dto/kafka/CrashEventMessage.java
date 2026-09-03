package com.iers.dispatch.dto.kafka;

import lombok.*;
import java.time.Instant;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CrashEventMessage {
    private String eventType;
    private String crashEventId;
    private String driverId;
    private String deviceId;
    private String driverName;
    private String driverPhone;
    private Double gpsLat;
    private Double gpsLng;
    private Double speed;
    private Double gForce;
    private Integer priorityScore;
    private String source;
    private String reason;
    private Instant timestamp;
}
