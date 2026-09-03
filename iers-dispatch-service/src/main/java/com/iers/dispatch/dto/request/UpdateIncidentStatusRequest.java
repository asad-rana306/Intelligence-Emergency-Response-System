package com.iers.dispatch.dto.request;

import com.iers.dispatch.entity.enums.IncidentStatus;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class UpdateIncidentStatusRequest {
    @NotNull private IncidentStatus status;
    private String hospitalId;
}
