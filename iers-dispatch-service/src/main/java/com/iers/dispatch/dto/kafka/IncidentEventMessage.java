package com.iers.dispatch.dto.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.Map;

/**
 * What Dispatch announces on the "incident-events" topic (key = incidentId).
 * Written exactly as in the API contract, "Shape of an incident event".
 * All times are UTC ISO text.
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class IncidentEventMessage {

    // The eight event types (kept here so nobody types them twice)
    public static final String INCIDENT_CREATED = "INCIDENT_CREATED";
    public static final String INCIDENT_OFFERED = "INCIDENT_OFFERED";
    public static final String INCIDENT_OFFER_EXPIRED = "INCIDENT_OFFER_EXPIRED";
    public static final String INCIDENT_ACCEPTED = "INCIDENT_ACCEPTED";
    public static final String INCIDENT_STATUS = "INCIDENT_STATUS";
    public static final String INCIDENT_STAND_DOWN = "INCIDENT_STAND_DOWN";
    public static final String BYSTANDER_ASSISTING = "BYSTANDER_ASSISTING";
    public static final String NO_RESPONDER_AVAILABLE = "NO_RESPONDER_AVAILABLE";
    /** PHASE 6: a chat message (text or voice) between the driver and the assigned responder. */
    public static final String CHAT_MESSAGE = "CHAT_MESSAGE";

    private String eventType;
    private String incidentId;
    private String crashEventId;
    private String driverId;
    /** The responder this event is about (offered or assigned). Null when there is none. */
    private String responderId;
    private String timestamp;
    private Map<String, Object> data;
}
