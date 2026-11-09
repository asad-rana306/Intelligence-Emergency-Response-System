package com.iers.dispatch.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * PHASE 6: one message between the driver and the assigned responder of one incident.
 * kind TEXT has text; kind VOICE has a recorded file on disk (audioPath, relative to the chat folder).
 * clientMessageId is made by the phone, so a resend after a bad connection is not saved twice.
 */
@Entity
@Table(name = "chat_messages",
        indexes = @Index(name = "idx_chat_incident", columnList = "incident_id, created_at"),
        uniqueConstraints = @UniqueConstraint(name = "uk_chat_client_id",
                columnNames = {"incident_id", "client_message_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatMessage {

    public static final String TEXT = "TEXT";
    public static final String VOICE = "VOICE";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    /** DRIVER or RESPONDER */
    @Column(name = "sender_role", nullable = false, length = 20)
    private String senderRole;

    /** TEXT or VOICE */
    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    @Column(name = "text", columnDefinition = "TEXT")
    private String text;

    @Column(name = "audio_path")
    private String audioPath;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "client_message_id", length = 64)
    private String clientMessageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
