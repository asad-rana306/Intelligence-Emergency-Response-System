package com.iers.realtime.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One message from Kafka, read the forgiving way: unknown fields are ignored, times may be
 * ISO text or numbers, and a missing "data" becomes an empty map.
 * It covers both shapes in the contract (crash event and incident event).
 */
public record KafkaEvent(String eventType,
                         String crashEventId,
                         String incidentId,
                         String driverId,
                         String responderId,
                         String deviceId,
                         String source,
                         String reason,
                         Instant timestamp,
                         Instant windowEndsAt,
                         Map<String, Object> data) {

    public KafkaEvent {
        if (data == null) {
            data = Map.of();
        }
    }

    public static KafkaEvent parse(ObjectMapper mapper, String json) throws JsonProcessingException {
        JsonNode n = mapper.readTree(json);
        if (n == null || !n.isObject()) {
            throw new IllegalArgumentException("Kafka message is not a JSON object");
        }
        JsonNode d = n.get("data");
        Map<String, Object> data = (d != null && d.isObject())
                ? mapper.convertValue(d, new TypeReference<LinkedHashMap<String, Object>>() { })
                : Map.of();
        return new KafkaEvent(
                text(n, "eventType"),
                text(n, "crashEventId"),
                text(n, "incidentId"),
                text(n, "driverId"),
                text(n, "responderId"),
                text(n, "deviceId"),
                text(n, "source"),
                text(n, "reason"),
                instant(n, "timestamp"),
                instant(n, "windowEndsAt"),
                data);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || v.isContainerNode()) {
            return null;
        }
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    /** ISO text ("2026-10-29T18:30:00Z") or a number (epoch seconds with decimals, or milliseconds). */
    private static Instant instant(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            if (v.isNumber()) {
                BigDecimal value = v.decimalValue();
                long whole = value.longValue();
                if (whole > 100_000_000_000L) {
                    return Instant.ofEpochMilli(whole);
                }
                long nanos = value.subtract(BigDecimal.valueOf(whole)).movePointRight(9).longValue();
                return Instant.ofEpochSecond(whole, nanos);
            }
            return Instant.parse(v.asText());
        } catch (DateTimeParseException | ArithmeticException e) {
            return null;
        }
    }
}
