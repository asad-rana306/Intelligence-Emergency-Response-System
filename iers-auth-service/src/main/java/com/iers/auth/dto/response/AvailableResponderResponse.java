package com.iers.auth.dto.response;

import com.iers.auth.entity.ResponderProfile;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class AvailableResponderResponse {
    private String responderId;
    private String userId;
    private String fullName;
    private String phone;
    private String vehicleId;
    private Double latitude;
    private Double longitude;

    public static AvailableResponderResponse from(ResponderProfile rp) {
        return AvailableResponderResponse.builder()
                .responderId(rp.getId().toString())
                .userId(rp.getUser().getId().toString())
                .fullName(rp.getUser().getFullName())
                .phone(rp.getUser().getPhone())
                .vehicleId(rp.getVehicleId())
                .latitude(rp.getCurrentLat())
                .longitude(rp.getCurrentLng())
                .build();
    }
}
