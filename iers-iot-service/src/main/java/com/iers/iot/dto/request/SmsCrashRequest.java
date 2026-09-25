package com.iers.iot.dto.request;

import lombok.*;

/**
 * Twilio SMS webhook payload. The crash data is JSON-encoded in the Body field.
 * The car GSM module formats: "CRASH|gForce|rollover|speed|lat|lng|deviceId"
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class SmsCrashRequest {
    private String From;
    private String Body;
    private String To;
}
