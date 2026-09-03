package com.iers.dispatch.dto.feign;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class AvailableResponderDto {
    private String responderId;
    private String userId;
    private String fullName;
    private String phone;
    private String vehicleId;
    private Double latitude;
    private Double longitude;
}
