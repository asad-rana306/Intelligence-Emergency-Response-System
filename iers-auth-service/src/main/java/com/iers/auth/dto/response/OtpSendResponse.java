package com.iers.auth.dto.response;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class OtpSendResponse {
    /** SENT, or QUEUED when the SMS gateway phone is offline and will send it later. */
    private String status;
    /** How long the code stays valid. */
    private long expiresInSeconds;
    /** When the app may ask for a new code. */
    private long resendAfterSeconds;
}
