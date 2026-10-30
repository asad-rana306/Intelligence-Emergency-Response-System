package com.iers.realtime.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("A crash event is read, unknown fields are ignored, times may be ISO text")
    void crashEvent() throws Exception {
        KafkaEvent e = KafkaEvent.parse(mapper, """
                {"eventType":"CRASH_PENDING","crashEventId":"c1","driverId":"d1","deviceId":"DEV-001",
                 "source":"HTTP","windowEndsAt":"2026-10-29T18:30:10Z","reason":null,
                 "timestamp":"2026-10-29T18:30:00Z","gForce":9.5,"driverName":"Ali"}
                """);

        assertThat(e.eventType()).isEqualTo("CRASH_PENDING");
        assertThat(e.crashEventId()).isEqualTo("c1");
        assertThat(e.driverId()).isEqualTo("d1");
        assertThat(e.deviceId()).isEqualTo("DEV-001");
        assertThat(e.source()).isEqualTo("HTTP");
        assertThat(e.reason()).isNull();
        assertThat(e.windowEndsAt()).isEqualTo(Instant.parse("2026-10-29T18:30:10Z"));
        assertThat(e.timestamp()).isEqualTo(Instant.parse("2026-10-29T18:30:00Z"));
        assertThat(e.data()).isEmpty();
    }

    @Test
    @DisplayName("An incident event keeps its data map, including nested objects")
    void incidentEvent() throws Exception {
        KafkaEvent e = KafkaEvent.parse(mapper, """
                {"eventType":"INCIDENT_ACCEPTED","incidentId":"i1","crashEventId":"c1","driverId":"d1",
                 "responderId":"r1","timestamp":"2026-10-29T18:30:25Z",
                 "data":{"incidentId":"i1","responder":{"name":"Bilal","phone":"+92300","vehicleId":"AMB-12"},"attempt":2}}
                """);

        assertThat(e.incidentId()).isEqualTo("i1");
        assertThat(e.responderId()).isEqualTo("r1");
        assertThat(e.data()).containsEntry("incidentId", "i1").containsEntry("attempt", 2);
        assertThat(e.data().get("responder")).isInstanceOf(java.util.Map.class);
    }

    @Test
    @DisplayName("Times written as numbers work: epoch seconds with decimals, and milliseconds")
    void numericTimes() throws Exception {
        KafkaEvent seconds = KafkaEvent.parse(mapper,
                "{\"eventType\":\"X\",\"timestamp\":1789253837.500000000}");
        assertThat(seconds.timestamp()).isEqualTo(Instant.ofEpochSecond(1789253837L, 500_000_000L));

        KafkaEvent millis = KafkaEvent.parse(mapper,
                "{\"eventType\":\"X\",\"timestamp\":1789253837123}");
        assertThat(millis.timestamp()).isEqualTo(Instant.ofEpochMilli(1789253837123L));
    }

    @Test
    @DisplayName("A time that cannot be read becomes null instead of an error")
    void badTime() throws Exception {
        KafkaEvent e = KafkaEvent.parse(mapper, "{\"eventType\":\"X\",\"timestamp\":\"yesterday\"}");
        assertThat(e.timestamp()).isNull();
    }

    @Test
    @DisplayName("Blank text and objects in text fields count as missing")
    void blankFields() throws Exception {
        KafkaEvent e = KafkaEvent.parse(mapper,
                "{\"eventType\":\"X\",\"driverId\":\"  \",\"reason\":{\"a\":1},\"data\":\"oops\"}");
        assertThat(e.driverId()).isNull();
        assertThat(e.reason()).isNull();
        assertThat(e.data()).isEmpty();
    }

    @Test
    @DisplayName("Bad JSON and non-objects are refused")
    void refused() {
        assertThatThrownBy(() -> KafkaEvent.parse(mapper, "not json {{"))
                .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> KafkaEvent.parse(mapper, "[1,2]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("data is never null")
    void dataNeverNull() {
        KafkaEvent e = new KafkaEvent("X", null, null, null, null, null, null, null, null, null, null);
        assertThat(e.data()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("CallKind reads any case and refuses unknown words")
    void callKind() {
        assertThat(CallKind.parse("offer")).isEqualTo(CallKind.OFFER);
        assertThat(CallKind.parse(" HANGUP ")).isEqualTo(CallKind.HANGUP);
        assertThat(CallKind.parse("shout")).isNull();
        assertThat(CallKind.parse(null)).isNull();
    }
}
