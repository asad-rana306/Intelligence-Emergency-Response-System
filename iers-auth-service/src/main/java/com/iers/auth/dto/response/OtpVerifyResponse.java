package com.iers.auth.dto.response;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class OtpVerifyResponse {

    public static final String WRONG_CODE = "WRONG_CODE";
    public static final String EXPIRED = "EXPIRED";
    public static final String TOO_MANY_ATTEMPTS = "TOO_MANY_ATTEMPTS";

    private boolean ok;
    /** Why it failed: WRONG_CODE, EXPIRED or TOO_MANY_ATTEMPTS. Null when ok. */
    private String reason;
    /** Guesses left for this code (only for WRONG_CODE). */
    private int remainingAttempts;

    public static OtpVerifyResponse verified() {
        return OtpVerifyResponse.builder().ok(true).build();
    }

    public static OtpVerifyResponse failed(String reason, int remainingAttempts) {
        return OtpVerifyResponse.builder().ok(false).reason(reason).remainingAttempts(remainingAttempts).build();
    }
}
