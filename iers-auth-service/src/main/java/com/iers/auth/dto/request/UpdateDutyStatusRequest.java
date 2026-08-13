package com.iers.auth.dto.request;

import com.iers.auth.entity.enums.DutyStatus;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class UpdateDutyStatusRequest {
    @NotNull
    private DutyStatus status;
}
