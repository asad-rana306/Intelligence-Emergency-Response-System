package com.iers.dispatch.dto.response;

import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.entity.enums.IncidentStatus;
import lombok.*;
import java.time.Instant;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class IncidentResponse {
    private String id;
    private String crashEventId;
    private String driverName;
    private Integer priorityScore;
    private Double gpsLat;
    private Double gpsLng;
    private IncidentStatus status;
    private String assignedResponderId;
    private String bloodType;
    private String allergies;
    private Boolean bystanderAssisting;
    private Instant createdAt;
    private Instant acceptedAt;

    public static IncidentResponse from(Incident i) {
        return IncidentResponse.builder()
                .id(i.getId().toString())
                .crashEventId(i.getCrashEventId().toString())
                .driverName(i.getDriverName())
                .priorityScore(i.getPriorityScore())
                .gpsLat(i.getGpsLat()).gpsLng(i.getGpsLng())
                .status(i.getStatus())
                .assignedResponderId(i.getAssignedResponderId() != null ? i.getAssignedResponderId().toString() : null)
                .bloodType(i.getBloodType()).allergies(i.getAllergies())
                .bystanderAssisting(i.getBystanderAssisting())
                .createdAt(i.getCreatedAt()).acceptedAt(i.getAcceptedAt())
                .build();
    }
}
