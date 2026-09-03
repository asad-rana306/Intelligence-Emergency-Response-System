package com.iers.dispatch.entity;

import com.iers.dispatch.entity.enums.DispatchResponseType;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dispatch_attempts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DispatchAttempt {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "responder_id", nullable = false)
    private UUID responderId;

    @Column(name = "attempt_number")
    private int attemptNumber;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private DispatchResponseType response = DispatchResponseType.PENDING;
}
