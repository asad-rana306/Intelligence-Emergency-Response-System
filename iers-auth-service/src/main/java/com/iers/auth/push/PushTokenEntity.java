package com.iers.auth.push;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per (user, device-token) pair.
 * A user can be signed in on multiple devices; each one has its own FCM token.
 * Tokens expire silently — we refresh them from the app and overwrite here.
 */
@Entity
@Table(name = "push_tokens", uniqueConstraints = @UniqueConstraint(columnNames = "token"))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PushTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The Firebase Cloud Messaging registration token. */
    @Column(nullable = false, length = 512)
    private String token;

    /** "android" | "ios" | "web" — useful for later, ignored for now. */
    @Column(length = 16)
    private String platform;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
