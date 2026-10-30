package com.iers.dispatch.dto.response;

import com.iers.dispatch.dto.Viewer;
import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.entity.enums.IncidentStatus;
import lombok.*;

import java.time.Instant;

/**
 * The incident as one person is allowed to see it.
 *
 *  - driverPhone: only the assigned responder, and only after accepting.
 *  - bloodType / allergies: the offered or assigned responder, and the driver.
 *  (Admins may open any incident, but get neither of these, exactly as the API contract says.)
 *  - responder: filled once the incident is accepted.
 *  - dispatchExpiresAt: only while the offer is open (DISPATCHED).
 *  - serverTime: the server clock, so a phone can fix its countdown.
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class IncidentResponse {

    private String id;
    private String crashEventId;
    private String driverId;
    private String driverName;
    private String driverPhone;
    private Integer priorityScore;
    private Double gpsLat;
    private Double gpsLng;
    private IncidentStatus status;
    private String assignedResponderId;
    private ResponderInfo responder;
    private String bloodType;
    private String allergies;
    private Boolean bystanderAssisting;
    private Instant dispatchExpiresAt;
    private Instant createdAt;
    private Instant acceptedAt;
    private Instant serverTime;

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ResponderInfo {
        private String name;
        private String phone;
        private String vehicleId;
    }

    public static IncidentResponse from(Incident i, Viewer viewer, Instant now) {
        boolean accepted = isAcceptedOrLater(i.getStatus());
        boolean isResponder = viewer.isResponderOf(i);
        boolean medicalVisible = viewer.isDriverOf(i) || isResponder;
        boolean phoneVisible = isResponder && accepted;

        ResponderInfo responderInfo = accepted
                ? ResponderInfo.builder()
                .name(i.getResponderName())
                .phone(i.getResponderPhone())
                .vehicleId(i.getResponderVehicleId())
                .build()
                : null;

        return IncidentResponse.builder()
                .id(i.getId().toString())
                .crashEventId(i.getCrashEventId().toString())
                .driverId(i.getDriverId() != null ? i.getDriverId().toString() : null)
                .driverName(i.getDriverName())
                .driverPhone(phoneVisible ? i.getDriverPhone() : null)
                .priorityScore(i.getPriorityScore())
                .gpsLat(i.getGpsLat()).gpsLng(i.getGpsLng())
                .status(i.getStatus())
                .assignedResponderId(i.getAssignedResponderId() != null
                        ? i.getAssignedResponderId().toString() : null)
                .responder(responderInfo)
                .bloodType(medicalVisible ? i.getBloodType() : null)
                .allergies(medicalVisible ? i.getAllergies() : null)
                .bystanderAssisting(i.getBystanderAssisting())
                .dispatchExpiresAt(i.getStatus() == IncidentStatus.DISPATCHED ? i.getDispatchExpiresAt() : null)
                .createdAt(i.getCreatedAt())
                .acceptedAt(i.getAcceptedAt())
                .serverTime(now)
                .build();
    }

    static boolean isAcceptedOrLater(IncidentStatus s) {
        return s == IncidentStatus.ACCEPTED
                || s == IncidentStatus.ARRIVED
                || s == IncidentStatus.EN_ROUTE_HOSPITAL
                || s == IncidentStatus.RESOLVED;
    }
}
