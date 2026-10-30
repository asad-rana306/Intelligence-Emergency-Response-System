package com.iers.iot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.iot.dto.request.CrashPayloadRequest;
import com.iers.iot.dto.response.CrashAckResponse;
import com.iers.iot.exception.CrashAccessDeniedException;
import com.iers.iot.exception.CrashEventNotFoundException;
import com.iers.iot.exception.GlobalExceptionHandler;
import com.iers.iot.service.CrashIngestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CrashController.class)
@Import(GlobalExceptionHandler.class)
class CrashControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private CrashIngestionService ingestionService;

    private CrashPayloadRequest sampleRequest() {
        return CrashPayloadRequest.builder()
                .gForce(8.5).rolloverAngle(45.0).speed(120.0)
                .gpsLat(37.77).gpsLng(-122.41)
                .timestamp(System.currentTimeMillis()).build();
    }

    // ══════════════════ POST /crash ══════════════════

    @Test
    @DisplayName("POST /api/telemetry/crash — 202 with windowEndsAt and serverTime")
    void ingestCrash_success() throws Exception {
        when(ingestionService.ingestHttpCrash(eq("DEV-001"), any()))
                .thenReturn(CrashAckResponse.builder()
                        .crashEventId(UUID.randomUUID().toString())
                        .status("RECEIVED")
                        .message("Crash registered.")
                        .windowEndsAt("2026-10-29T18:30:10Z")
                        .serverTime("2026-10-29T18:30:00Z").build());

        mockMvc.perform(post("/api/telemetry/crash")
                        .header("X-Device-Id", "DEV-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.windowEndsAt").value("2026-10-29T18:30:10Z"))
                .andExpect(jsonPath("$.serverTime").value("2026-10-29T18:30:00Z"));
    }

    @Test
    @DisplayName("POST /api/telemetry/crash — accepted without a timestamp")
    void ingestCrash_noTimestamp() throws Exception {
        when(ingestionService.ingestHttpCrash(eq("DEV-001"), any()))
                .thenReturn(CrashAckResponse.builder().status("RECEIVED").build());

        String body = """
                {"gForce": 12.4, "rolloverAngle": 8, "speed": 41, "gpsLat": 31.5204, "gpsLng": 74.3587,
                 "sensorData": {"accelX": 11.9, "accelY": 2.1, "accelZ": 1.2, "gyroRoll": 35, "gyroPitch": 12}}
                """;

        mockMvc.perform(post("/api/telemetry/crash")
                        .header("X-Device-Id", "DEV-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("POST /api/telemetry/crash — 200 OK on duplicate")
    void ingestCrash_duplicate() throws Exception {
        when(ingestionService.ingestHttpCrash(any(), any()))
                .thenReturn(CrashAckResponse.builder()
                        .status("DUPLICATE")
                        .message("Already received").build());

        mockMvc.perform(post("/api/telemetry/crash")
                        .header("X-Device-Id", "DEV-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));
    }

    @Test
    @DisplayName("POST /api/telemetry/crash — 400 when a required field is missing")
    void ingestCrash_missingField() throws Exception {
        String body = "{\"rolloverAngle\": 8, \"speed\": 41, \"gpsLat\": 31.5, \"gpsLng\": 74.3}";

        mockMvc.perform(post("/api/telemetry/crash")
                        .header("X-Device-Id", "DEV-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("gForce")));
    }

    @Test
    @DisplayName("POST /api/telemetry/crash — 400 (not 500) when the JSON is broken")
    void ingestCrash_brokenJson() throws Exception {
        mockMvc.perform(post("/api/telemetry/crash")
                        .header("X-Device-Id", "DEV-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest());
    }

    // ══════════════════ POST /crash/cancel (box button) ══════════════════

    @Test
    @DisplayName("POST /api/telemetry/crash/cancel — device cancel, 200 CANCELLED")
    void deviceCancel_success() throws Exception {
        UUID id = UUID.randomUUID();
        when(ingestionService.cancelByDevice("DEV-001"))
                .thenReturn(CrashAckResponse.builder()
                        .crashEventId(id.toString()).status("CANCELLED").build());

        mockMvc.perform(post("/api/telemetry/crash/cancel").header("X-Device-Id", "DEV-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.crashEventId").value(id.toString()));
    }

    @Test
    @DisplayName("POST /api/telemetry/crash/cancel — nothing pending is still 200")
    void deviceCancel_nothingPending() throws Exception {
        when(ingestionService.cancelByDevice("DEV-001"))
                .thenReturn(CrashAckResponse.builder().status("NO_PENDING_CRASH").build());

        mockMvc.perform(post("/api/telemetry/crash/cancel").header("X-Device-Id", "DEV-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_PENDING_CRASH"));
    }

    // ══════════════════ POST /crash/{id}/cancel (driver phone) ══════════════════

    @Test
    @DisplayName("POST /api/telemetry/crash/{id}/cancel — 200 OK for the owner")
    void cancelCrash_success() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(ingestionService.cancelCrash(eventId, userId))
                .thenReturn(CrashAckResponse.builder()
                        .crashEventId(eventId.toString())
                        .status("CANCELLED").build());

        mockMvc.perform(post("/api/telemetry/crash/{id}/cancel", eventId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("POST /api/telemetry/crash/{id}/cancel — 403 for someone else's crash")
    void cancelCrash_forbidden() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(ingestionService.cancelCrash(eventId, userId))
                .thenThrow(new CrashAccessDeniedException("This crash does not belong to you"));

        mockMvc.perform(post("/api/telemetry/crash/{id}/cancel", eventId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This crash does not belong to you"));
    }

    @Test
    @DisplayName("POST /api/telemetry/crash/{id}/cancel — 404 when not found")
    void cancelCrash_notFound() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(ingestionService.cancelCrash(eventId, userId))
                .thenThrow(new CrashEventNotFoundException("Not found"));

        mockMvc.perform(post("/api/telemetry/crash/{id}/cancel", eventId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/telemetry/crash/{id}/cancel — a bad ID like 1 is a clear 400")
    void cancelCrash_badId() throws Exception {
        mockMvc.perform(post("/api/telemetry/crash/{id}/cancel", "1")
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid value '1'")));

        verify(ingestionService, never()).cancelCrash(any(), any());
    }

    @Test
    @DisplayName("POST /api/telemetry/crash/{id}/cancel — 400 when the user header is missing")
    void cancelCrash_missingUserHeader() throws Exception {
        mockMvc.perform(post("/api/telemetry/crash/{id}/cancel", UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("X-User-Id")));
    }

    // ══════════════════ POST /crash/{id}/late-cancel ══════════════════

    @Test
    @DisplayName("POST /api/telemetry/crash/{id}/late-cancel — 200 OK with LATE_CANCELLED")
    void lateCancelCrash_success() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(ingestionService.lateCancelCrash(eventId, userId))
                .thenReturn(CrashAckResponse.builder()
                        .crashEventId(eventId.toString())
                        .status("LATE_CANCELLED").build());

        mockMvc.perform(post("/api/telemetry/crash/{id}/late-cancel", eventId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LATE_CANCELLED"));
    }

    @Test
    @DisplayName("POST /api/telemetry/crash/{id}/late-cancel — 403 for someone else's crash")
    void lateCancelCrash_forbidden() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(ingestionService.lateCancelCrash(eventId, userId))
                .thenThrow(new CrashAccessDeniedException("This crash does not belong to you"));

        mockMvc.perform(post("/api/telemetry/crash/{id}/late-cancel", eventId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isForbidden());
    }

    // ══════════════════ POST /crash/sms ══════════════════

    @Test
    @DisplayName("POST /api/telemetry/crash/sms — the pipe format is parsed and ingested")
    void sms_success() throws Exception {
        when(ingestionService.ingestSmsCrash(eq("DEV-001"), eq(12.4), eq(8.0), eq(41.0), eq(31.5204), eq(74.3587)))
                .thenReturn(CrashAckResponse.builder().crashEventId(UUID.randomUUID().toString())
                        .status("CONFIRMED").build());

        mockMvc.perform(post("/api/telemetry/crash/sms")
                        .param("From", "+923001234567")
                        .param("Body", "CRASH|12.4|8|41|31.5204|74.3587|DEV-001"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("POST /api/telemetry/crash/sms — a wrong format is a 400 INVALID")
    void sms_wrongFormat() throws Exception {
        mockMvc.perform(post("/api/telemetry/crash/sms")
                        .param("From", "+923001234567")
                        .param("Body", "CRASH LAT:31.5204 LNG:74.3587 SPD:80"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("INVALID"));
    }

    @Test
    @DisplayName("POST /api/telemetry/crash/sms — a missing body is a 400 INVALID")
    void sms_missingBody() throws Exception {
        mockMvc.perform(post("/api/telemetry/crash/sms").param("From", "+923001234567"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("INVALID"));
    }

    @Test
    @DisplayName("POST /api/telemetry/crash/sms — numbers that cannot be read are a 400 PARSE_ERROR")
    void sms_badNumbers() throws Exception {
        mockMvc.perform(post("/api/telemetry/crash/sms")
                        .param("From", "+923001234567")
                        .param("Body", "CRASH|abc|8|41|31.5204|74.3587|DEV-001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("PARSE_ERROR"));
    }
}
