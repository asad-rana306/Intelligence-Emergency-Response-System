package com.iers.dispatch.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Local mirror of responder GPS positions for PostGIS spatial queries.
 * Synced from Auth Service. Uses plain lat/lng columns with PostGIS
 * functions in native queries for distance calculation.
 */
@Entity
@Table(name = "responder_locations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResponderLocation {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "responder_id", nullable = false, unique = true)
    private UUID responderId;

    @Column(name = "user_name")
    private String userName;

    @Column(name = "vehicle_id")
    private String vehicleId;

    @Column(name = "duty_status")
    private String dutyStatus;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "last_updated")
    private Instant lastUpdated;
}
