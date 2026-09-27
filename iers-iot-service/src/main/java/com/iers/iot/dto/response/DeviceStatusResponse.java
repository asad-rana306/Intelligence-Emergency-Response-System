package com.iers.iot.dto.response;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class DeviceStatusResponse {
    private String deviceId;
    private String userId;
    private boolean paired;
    private String lastHeartbeat;
}
