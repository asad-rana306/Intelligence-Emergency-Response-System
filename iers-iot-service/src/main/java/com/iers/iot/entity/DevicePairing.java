package com.iers.iot.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_pairings", uniqueConstraints = {
    @UniqueConstraint(columnNames = "device_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DevicePairing {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "device_id", nullable = false, unique = true)
    private String deviceId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Cached from Auth at pairing time — carried in Kafka messages for Dispatch fallback */
    @Column(name = "driver_name")
    private String driverName;

    @Column(name = "driver_phone")
    private String driverPhone;

    @CreationTimestamp
    @Column(name = "paired_at", updatable = false)
    private Instant pairedAt;
}
