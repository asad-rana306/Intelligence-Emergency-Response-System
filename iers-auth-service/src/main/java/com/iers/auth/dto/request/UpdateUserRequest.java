package com.iers.auth.dto.request;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class UpdateUserRequest {
    private String fullName;
    private String phone;
    private String deviceId;
}
