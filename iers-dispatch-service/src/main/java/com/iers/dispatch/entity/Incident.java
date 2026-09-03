package com.iers.dispatch.entity;

import com.iers.dispatch.entity.enums.IncidentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incidents", indexes = {
        @Index(name = "idx_incident_crash", columnList = "crash_event_id"),
        @Index(name = "idx_incident_status", columnList = "status")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "crash_event_id", nullable = false, unique = true)
    private UUID crashEventId;

    @Column(name = "driver_id")
    private UUID driverId;

    @Column(name = "driver_name")
    private String driverName;

    @Column(name = "driver_phone")
    private String driverPhone;

    @Column(name = "priority_score")
    private Integer priorityScore;

    @Column(name = "gps_lat")
    private Double gpsLat;

    @Column(name = "gps_lng")
    private Double gpsLng;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentStatus status;

    @Column(name = "assigned_responder_id")
    private UUID assignedResponderId;

    @Column(name = "hospital_id")
    private String hospitalId;

    /** Medical profile snapshot — fallback data cached at dispatch time */
    @Column(name = "blood_type")
    private String bloodType;

    @Column(columnDefinition = "TEXT")
    private String allergies;

    @Column(name = "bystander_assisting")
    @Builder.Default
    private Boolean bystanderAssisting = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "en_route_at")
    private Instant enRouteAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
