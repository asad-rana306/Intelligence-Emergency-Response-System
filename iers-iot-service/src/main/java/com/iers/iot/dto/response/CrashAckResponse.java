package com.iers.iot.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CrashAckResponse {
    private String crashEventId;
    private String status;
    private String message;

    /** UTC text. Only on a new crash: when the 10-second cancel window ends. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String windowEndsAt;

    /** UTC text. The server clock, so phones can count down without trusting their own clock. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String serverTime;
}

