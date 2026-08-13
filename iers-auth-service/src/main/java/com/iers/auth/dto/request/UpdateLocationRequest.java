package com.iers.auth.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class UpdateLocationRequest {
    @NotNull
    private Double latitude;

    @NotNull
    private Double longitude;
}
