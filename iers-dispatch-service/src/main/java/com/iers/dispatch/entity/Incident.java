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

    /**
     * The responder's USER id (the same id they send in X-User-Id).
     * While status is DISPATCHED this is the OFFERED responder.
     * From ACCEPTED onwards it is the ASSIGNED responder.
     * It is null while DISPATCHING (nobody is holding the incident).
     */
    @Column(name = "assigned_responder_id")
    private UUID assignedResponderId;

    /** Snapshot of the offered/assigned responder, taken when the offer is made. */
    @Column(name = "responder_name")
    private String responderName;

    @Column(name = "responder_phone")
    private String responderPhone;

    @Column(name = "responder_vehicle_id")
    private String responderVehicleId;

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

    /** When the current offer ends. Set only while status is DISPATCHED. */
    @Column(name = "dispatch_expires_at")
    private Instant dispatchExpiresAt;

    /** When the sweep should try to find a responder again. Set only while status is DISPATCHING. */
    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    /** True once NO_RESPONDER_AVAILABLE has been announced (so it is sent only once). */
    @Column(name = "no_responder_notified")
    @Builder.Default
    private Boolean noResponderNotified = false;

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
