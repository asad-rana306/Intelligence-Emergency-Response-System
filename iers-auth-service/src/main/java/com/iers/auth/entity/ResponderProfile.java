package com.iers.auth.entity;

import com.iers.auth.entity.enums.DutyStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "responder_profiles")
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
}
