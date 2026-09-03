package com.iers.dispatch.dto.feign;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class EmergencyContactDto {
    private String id;
    private String contactName;
    private String phone;
    private String relationship;
}
