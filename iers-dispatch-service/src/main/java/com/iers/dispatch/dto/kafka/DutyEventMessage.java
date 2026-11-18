package com.iers.dispatch.dto.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What Auth announces on the "duty-events" topic (JSON, all values are text, key = userId).
 *
 * type    DUTY_CHANGED (a person or Dispatch changed the status) or DUTY_EXPIRED (the system put the
 *         responder OFF duty)
 * reason  for DUTY_EXPIRED: MAX_DURATION (shift over) or HEARTBEAT_LOST (phone went silent)
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class DutyEventMessage {

    public static final String DUTY_CHANGED = "DUTY_CHANGED";
    public static final String DUTY_EXPIRED = "DUTY_EXPIRED";

    private String type;
    private String userId;
    private String status;
    private String reason;
    private String occurredAt;
}
