package com.iers.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class PushTokenRequest {
    /** The Firebase Cloud Messaging registration token of this phone. */
    @NotBlank @Size(max = 512)
    private String token;

    /** android | ios | web. Optional, defaults to android. */
    @Size(max = 16)
    private String platform;
}
