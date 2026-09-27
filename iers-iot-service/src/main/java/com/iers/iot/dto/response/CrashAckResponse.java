package com.iers.iot.dto.response;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CrashAckResponse {
    private String crashEventId;
    private String status;
    private String message;
}
