package com.iers.iot.entity;

import com.iers.iot.entity.enums.UploadMediaType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bystander_media")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BystanderMedia {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "crash_event_id", nullable = false)
    private UUID crashEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false)
    private UploadMediaType mediaType;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "uploaded_by_description")
    private String uploadedByDescription;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
