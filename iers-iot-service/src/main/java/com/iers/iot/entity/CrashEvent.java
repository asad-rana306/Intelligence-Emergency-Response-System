package com.iers.iot.entity;

import com.iers.iot.entity.enums.CrashEventStatus;
import com.iers.iot.entity.enums.CrashSource;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "crash_events", indexes = {
        @Index(name = "idx_crash_device", columnList = "device_id"),
        @Index(name = "idx_crash_driver", columnList = "driver_id"),
        @Index(name = "idx_crash_status", columnList = "status")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CrashEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "driver_id")
    private UUID driverId;

    @Column(name = "driver_name")
    private String driverName;

    @Column(name = "driver_phone")
    private String driverPhone;

    @Column(name = "g_force")
    private Double gForce;

    @Column(name = "rollover_angle")
    private Double rolloverAngle;

    private Double speed;

    @Column(name = "gps_lat")
    private Double gpsLat;

    @Column(name = "gps_lng")
    private Double gpsLng;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private Map<String, Object> rawPayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CrashSource source;

    @Column(name = "ml_severity")
    private String mlSeverity;

    @Column(name = "priority_score")
    private Integer priorityScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CrashEventStatus status;

    @Column(name = "cancellation_deadline")
    private Instant cancellationDeadline;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
