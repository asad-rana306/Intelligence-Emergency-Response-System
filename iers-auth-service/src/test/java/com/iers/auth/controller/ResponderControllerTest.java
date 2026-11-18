package com.iers.auth.controller;

import com.iers.auth.dto.response.ResponderProfileResponse;
import com.iers.auth.entity.enums.DutyStatus;
import com.iers.auth.exception.GlobalExceptionHandler;
import com.iers.auth.exception.ResourceNotFoundException;
import com.iers.auth.service.ResponderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResponderController.class)
@Import(GlobalExceptionHandler.class)
class ResponderControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ResponderService responderService;

    private final UUID userId = UUID.randomUUID();

    private ResponderProfileResponse onDuty(long secondsRemaining) {
        return ResponderProfileResponse.builder()
                .id(UUID.randomUUID().toString()).userId(userId.toString())
                .dutyStatus(DutyStatus.ON_DUTY)
                .dutyStartedAt(Instant.parse("2026-01-01T10:00:00Z"))
                .dutyExpiresAt(Instant.parse("2026-01-01T11:00:00Z"))
                .secondsRemaining(secondsRemaining)
                .serverTime(Instant.parse("2026-01-01T10:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("POST /api/responders/heartbeat — 200 with status and seconds left")
    void heartbeat_success() throws Exception {
        when(responderService.heartbeat(userId)).thenReturn(onDuty(1800));

        mockMvc.perform(post("/api/responders/heartbeat").header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dutyStatus").value("ON_DUTY"))
                .andExpect(jsonPath("$.secondsRemaining").value(1800))
                .andExpect(jsonPath("$.dutyExpiresAt").value("2026-01-01T11:00:00Z"));
    }

    @Test
    @DisplayName("POST /api/responders/heartbeat — 404 when the user is not a responder")
    void heartbeat_notResponder() throws Exception {
        when(responderService.heartbeat(userId)).thenThrow(new ResourceNotFoundException("no profile"));

        mockMvc.perform(post("/api/responders/heartbeat").header("X-User-Id", userId.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/responders/me — 200 with my duty info")
    void me_success() throws Exception {
        when(responderService.getProfile(userId)).thenReturn(onDuty(600));

        mockMvc.perform(get("/api/responders/me").header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secondsRemaining").value(600));
    }

    @Test
    @DisplayName("PUT /api/responders/status — 200 and the new shift is returned")
    void updateStatus_success() throws Exception {
        when(responderService.updateDutyStatus(any(), any())).thenReturn(onDuty(3600));

        mockMvc.perform(put("/api/responders/status")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ON_DUTY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secondsRemaining").value(3600));

        verify(responderService).updateDutyStatus(any(), any());
    }

    @Test
    @DisplayName("PUT /api/responders/status — 400 when the status is not a known value")
    void updateStatus_badValue() throws Exception {
        mockMvc.perform(put("/api/responders/status")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SLEEPING\"}"))
                .andExpect(status().isBadRequest());
    }
}
