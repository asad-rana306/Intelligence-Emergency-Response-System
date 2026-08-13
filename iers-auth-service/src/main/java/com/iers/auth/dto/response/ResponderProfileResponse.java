package com.iers.auth.dto.response;

import com.iers.auth.entity.ResponderProfile;
import com.iers.auth.entity.enums.DutyStatus;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ResponderProfileResponse {
    private String id;
    private String userId;
    private DutyStatus dutyStatus;
    private String vehicleId;
    private Double currentLat;
    private Double currentLng;

    public static ResponderProfileResponse from(ResponderProfile rp) {
        return ResponderProfileResponse.builder()
                .id(rp.getId().toString())
                .userId(rp.getUser().getId().toString())
                .dutyStatus(rp.getDutyStatus())
                .vehicleId(rp.getVehicleId())
                .currentLat(rp.getCurrentLat())
                .currentLng(rp.getCurrentLng())
                .build();
    }
}
