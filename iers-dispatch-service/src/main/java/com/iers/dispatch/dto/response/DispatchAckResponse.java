package com.iers.dispatch.dto.response;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class DispatchAckResponse {
    private String incidentId;
    private String status;
    private String message;
}
