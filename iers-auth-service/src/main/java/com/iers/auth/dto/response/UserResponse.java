package com.iers.auth.dto.response;

import com.iers.auth.entity.enums.Role;
import com.iers.auth.entity.User;
import lombok.*;

import java.time.Instant;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class UserResponse {
    private String id;
    private String email;
    private String fullName;
    private String phone;
    private Role role;
    private Instant createdAt;

    public static UserResponse from(User user) {
        return UserResponse.builder()
                .id(user.getId().toString())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
