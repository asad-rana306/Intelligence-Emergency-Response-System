package com.iers.iot.dto.kafka;

import lombok.*;
import java.time.Instant;

/**
 * Payload published to the "crash-events" Kafka topic.
 * Carries both CRASH_DETECTED and CRASH_CANCELLED event types.
 */
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
