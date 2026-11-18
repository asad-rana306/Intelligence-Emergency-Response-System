package com.iers.dispatch.push;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One push notification. {@code data} always has a "type" the Flutter app switches on:
 * OFFER, ACCEPTED, STAND_DOWN, OFF_DUTY. All values are strings (an FCM rule).
 *
 * @param urgent true = high priority + the "iers_offers" Android channel (wakes a sleeping phone)
 * @param ttl    how long FCM may keep the message for an offline phone (null = FCM default)
 */
public record PushMessage(String title, String body, Map<String, String> data, boolean urgent, Duration ttl) {

    public static final String OFFER = "OFFER";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String STAND_DOWN = "STAND_DOWN";
    public static final String OFF_DUTY = "OFF_DUTY";

    /** An ambulance offer for a responder. */
    public static PushMessage offer(UUID incidentId, Double lat, Double lng, String driverName, Duration ttl) {
        Map<String, String> data = typed(OFFER);
        data.put("incidentId", incidentId.toString());
        data.put("lat", lat == null ? "" : String.valueOf(lat));
        data.put("lng", lng == null ? "" : String.valueOf(lng));
        data.put("driverName", driverName == null ? "" : driverName);
        return new PushMessage("EMERGENCY - accept now", "A crash was reported near you. Tap to open.",
                data, true, ttl);
    }

    /** "Help is on the way" for the driver. */
    public static PushMessage accepted(UUID incidentId, String responderName) {
        Map<String, String> data = typed(ACCEPTED);
        data.put("incidentId", incidentId.toString());
        data.put("responderName", responderName == null ? "" : responderName);
        return new PushMessage("Help is on the way",
                responderName == null || responderName.isBlank()
                        ? "A responder accepted your emergency."
                        : responderName + " accepted your emergency and is on the way.",
                data, true, null);
    }

    /** "Stand down" for a responder: the driver cancelled (false alarm). */
    public static PushMessage standDown(UUID incidentId) {
        Map<String, String> data = typed(STAND_DOWN);
        data.put("incidentId", incidentId.toString());
        return new PushMessage("Emergency cancelled", "The driver is safe. You can stand down.",
                data, true, null);
    }

    /** "You are now OFF duty". {@code reason} is MAX_DURATION or HEARTBEAT_LOST (may be null). */
    public static PushMessage offDuty(String reason) {
        Map<String, String> data = typed(OFF_DUTY);
        data.put("reason", reason == null ? "" : reason);
        String body = "HEARTBEAT_LOST".equals(reason)
                ? "We lost contact with your phone, so you were put OFF duty. Open the app to go on duty again."
                : "Your 1-hour shift is over. Open the app to go on duty again.";
        return new PushMessage("You are now OFF duty", body, data, false, null);
    }

    private static Map<String, String> typed(String type) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", type);
        return data;
    }
}
