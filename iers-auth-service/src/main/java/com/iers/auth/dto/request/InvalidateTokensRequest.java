package com.iers.auth.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

/** Sent by Dispatch: tokens that Firebase said are no longer valid. */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class InvalidateTokensRequest {
    @NotEmpty @Size(max = 500)
    private List<String> tokens;
}
