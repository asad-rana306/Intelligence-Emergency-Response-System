package com.iers.dispatch.dto.request;

import lombok.*;

/** PHASE 6: body of POST /api/incidents/{id}/messages */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class SendChatTextRequest {
    private String text;
    /** Made by the phone (any unique text, max 64). A resend with the same value is not saved twice. */
    private String clientMessageId;
}
