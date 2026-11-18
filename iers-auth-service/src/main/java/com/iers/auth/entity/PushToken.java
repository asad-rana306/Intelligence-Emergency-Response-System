package com.iers.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One Firebase (FCM) token of one phone. A user can be signed in on several phones,
 * so one user has several rows. The token itself is unique: if the same phone logs in
 * as another user, the row moves to that user.
 */
@Entity
@Table(name = "push_tokens",
        uniqueConstraints = @UniqueConstraint(name = "uk_push_tokens_token", columnNames = "token"),
        indexes = @Index(name = "idx_push_tokens_user", columnList = "user_id"))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PushToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Plain id (no join), so a lookup by user is one cheap indexed query. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 512)
    private String token;

    /** android | ios | web */
    @Column(length = 16)
    private String platform;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
