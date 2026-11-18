package com.iers.auth.entity;

import com.iers.auth.entity.enums.DutyStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * DynamicUpdate: Hibernate writes only the columns that changed. This matters because the
 * heartbeat, the location and the duty sweep all touch the same row at about the same time;
 * each one must not overwrite the columns of the others.
 */
@Entity
@Table(name = "responder_profiles",
        indexes = @Index(name = "idx_responder_duty", columnList = "duty_status"))
@DynamicUpdate
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ResponderProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "duty_status", nullable = false)
    @Builder.Default
    private DutyStatus dutyStatus = DutyStatus.OFF_DUTY;

    @Column(name = "vehicle_id")
    private String vehicleId;

    @Column(name = "current_lat")
    private Double currentLat;

    @Column(name = "current_lng")
    private Double currentLng;

    @Column(name = "zone_id")
    private String zoneId;

    @UpdateTimestamp
    @Column(name = "last_location_update")
    private Instant lastLocationUpdate;

    /** When the current shift started. Null when OFF duty. */
    @Column(name = "duty_started_at")
    private Instant dutyStartedAt;

    /** When the shift ends and the responder is put OFF duty. Null when OFF duty. */
    @Column(name = "duty_expires_at")
    private Instant dutyExpiresAt;

    /** Last sign of life from the phone (heartbeat or location). Null when OFF duty. */
    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;
}
