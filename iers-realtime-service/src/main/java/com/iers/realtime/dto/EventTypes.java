package com.iers.realtime.dto;

/** Every event name in one place, so nobody types them twice. */
public final class EventTypes {

    private EventTypes() { }

    // From the crash-events topic (IoT service)
    public static final String CRASH_PENDING = "CRASH_PENDING";
    public static final String CRASH_DETECTED = "CRASH_DETECTED";
    public static final String CRASH_CANCELLED = "CRASH_CANCELLED";
    public static final String CRASH_DISMISSED = "CRASH_DISMISSED";

    // From the incident-events topic (Dispatch service)
    public static final String INCIDENT_CREATED = "INCIDENT_CREATED";
    public static final String INCIDENT_OFFERED = "INCIDENT_OFFERED";
    public static final String INCIDENT_OFFER_EXPIRED = "INCIDENT_OFFER_EXPIRED";
    public static final String INCIDENT_ACCEPTED = "INCIDENT_ACCEPTED";
    public static final String INCIDENT_STATUS = "INCIDENT_STATUS";
    public static final String INCIDENT_STAND_DOWN = "INCIDENT_STAND_DOWN";
    public static final String BYSTANDER_ASSISTING = "BYSTANDER_ASSISTING";
    public static final String NO_RESPONDER_AVAILABLE = "NO_RESPONDER_AVAILABLE";

    // Made by this service
    public static final String SNAPSHOT = "SNAPSHOT";
    public static final String CALL_ERROR = "CALL_ERROR";
}
