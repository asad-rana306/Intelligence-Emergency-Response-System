package com.iers.iot.dto.kafka;

import lombok.*;
import java.time.Instant;

/**
 * Payload published to the "crash-events" Kafka topic.
 * Event types: CRASH_PENDING, CRASH_DETECTED, CRASH_CANCELLED, CRASH_DISMISSED.
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

    /** NEW (Phase 2). Only set on CRASH_PENDING: when the 10-second window ends. */
    private Instant windowEndsAt;
}