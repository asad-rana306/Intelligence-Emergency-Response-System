package com.iers.auth.dto.response;

import com.iers.auth.entity.ResponderProfile;
import com.iers.auth.entity.enums.DutyStatus;
import lombok.*;

import java.time.Duration;
import java.time.Instant;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ResponderProfileResponse {
    private String id;
    private String userId;
    private DutyStatus dutyStatus;
    private String vehicleId;
    private Double currentLat;
    private Double currentLng;

    /** When the current shift started (null when OFF duty). */
    private Instant dutyStartedAt;
    /** When the shift ends (null when OFF duty). */
    private Instant dutyExpiresAt;
    /** Seconds left in the shift, never negative. Null when there is no shift. */
    private Long secondsRemaining;
    /** The server clock, so the app can fix a wrong phone clock. */
    private Instant serverTime;

    public static ResponderProfileResponse from(ResponderProfile rp) {
        return from(rp, Instant.now());
    }

    public static ResponderProfileResponse from(ResponderProfile rp, Instant now) {
        Long remaining = null;
        if (rp.getDutyExpiresAt() != null) {
            remaining = Math.max(0L, Duration.between(now, rp.getDutyExpiresAt()).getSeconds());
        }
        return ResponderProfileResponse.builder()
                .id(rp.getId().toString())
                .userId(rp.getUser().getId().toString())
                .dutyStatus(rp.getDutyStatus())
                .vehicleId(rp.getVehicleId())
                .currentLat(rp.getCurrentLat())
                .currentLng(rp.getCurrentLng())
                .dutyStartedAt(rp.getDutyStartedAt())
                .dutyExpiresAt(rp.getDutyExpiresAt())
                .secondsRemaining(remaining)
                .serverTime(now)
                .build();
    }
}
