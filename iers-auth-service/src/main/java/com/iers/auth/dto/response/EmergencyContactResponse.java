package com.iers.auth.dto.response;

import com.iers.auth.entity.EmergencyContact;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class EmergencyContactResponse {
    private String id;
    private String contactName;
    private String phone;
    private String relationship;

    public static EmergencyContactResponse from(EmergencyContact ec) {
        return EmergencyContactResponse.builder()
                .id(ec.getId().toString())
                .contactName(ec.getContactName())
                .phone(ec.getPhone())
                .relationship(ec.getRelationship())
                .build();
    }
}
