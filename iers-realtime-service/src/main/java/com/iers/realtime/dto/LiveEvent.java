package com.iers.realtime.dto;

import java.util.Map;

/**
 * What a phone receives on /user/queue/events. Same outer shape for every event:
 *
 *   { "type": "INCIDENT_OFFERED", "eventId": "...", "serverTime": "2026-10-29T18:30:12Z", "data": { ... } }
 *
 * Times are UTC ISO text (already text here, so the JSON never depends on a date setting).
 * The same Kafka event always gets the same eventId, so a phone can drop a repeat.
 */
public record LiveEvent(String type, String eventId, String serverTime, Map<String, Object> data) { }
