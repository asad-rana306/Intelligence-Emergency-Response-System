package com.iers.iot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.iot.dto.request.CrashPayloadRequest;
import com.iers.iot.dto.response.CrashAckResponse;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CrashController.class)
@Import(GlobalExceptionHandler.class)
class CrashControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private CrashIngestionService ingestionService;

    @Test
    @DisplayName("POST /api/telemetry/crash — 202 Accepted on success")
    void ingestCrash_success() throws Exception {
        CrashPayloadRequest request = CrashPayloadRequest.builder()
                .gForce(8.5).rolloverAngle(45.0).speed(120.0)
                .gpsLat(37.77).gpsLng(-122.41)
                .timestamp(System.currentTimeMillis()).build();

        when(ingestionService.ingestHttpCrash(eq("DEV-001"), any()))
                .thenReturn(CrashAckResponse.builder()
                        .crashEventId(UUID.randomUUID().toString())
                        .status("RECEIVED")
                        .message("Crash registered.").build());

        mockMvc.perform(post("/api/telemetry/crash")
                        .header("X-Device-Id", "DEV-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    @DisplayName("POST /api/telemetry/crash — 200 OK on duplicate")
    void ingestCrash_duplicate() throws Exception {
        CrashPayloadRequest request = CrashPayloadRequest.builder()
                .gForce(8.5).rolloverAngle(45.0).speed(120.0)
                .gpsLat(37.77).gpsLng(-122.41)
                .timestamp(System.currentTimeMillis()).build();

        when(ingestionService.ingestHttpCrash(any(), any()))
                .thenReturn(CrashAckResponse.builder()
                        .status("DUPLICATE")
                        .message("Already received").build());

        mockMvc.perform(post("/api/telemetry/crash")
                        .header("X-Device-Id", "DEV-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));
    }

    @Test
    @DisplayName("POST /api/telemetry/crash/{id}/cancel — 200 OK")
    void cancelCrash_success() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(ingestionService.cancelCrash(eventId))
                .thenReturn(CrashAckResponse.builder()
                        .crashEventId(eventId.toString())
                        .status("CANCELLED").build());

        mockMvc.perform(post("/api/telemetry/crash/{id}/cancel", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("POST /api/telemetry/crash/{id}/cancel — 404 when not found")
    void cancelCrash_notFound() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(ingestionService.cancelCrash(eventId))
                .thenThrow(new CrashEventNotFoundException("Not found"));

        mockMvc.perform(post("/api/telemetry/crash/{id}/cancel", eventId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/telemetry/crash/{id}/late-cancel — 200 OK with LATE_CANCELLED")
    void lateCancelCrash_success() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(ingestionService.lateCancelCrash(eventId))
                .thenReturn(CrashAckResponse.builder()
                        .crashEventId(eventId.toString())
                        .status("LATE_CANCELLED").build());

        mockMvc.perform(post("/api/telemetry/crash/{id}/late-cancel", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LATE_CANCELLED"));
    }
}
