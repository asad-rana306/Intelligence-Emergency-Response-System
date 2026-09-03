package com.iers.dispatch.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.dispatch.dto.request.UpdateIncidentStatusRequest;
import com.iers.dispatch.dto.response.DispatchAckResponse;
import com.iers.dispatch.entity.enums.IncidentStatus;
import com.iers.dispatch.exception.GlobalExceptionHandler;
import com.iers.dispatch.exception.IncidentNotFoundException;
import com.iers.dispatch.service.DispatchOrchestrator;
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

@WebMvcTest(DispatchController.class)
@Import(GlobalExceptionHandler.class)
class DispatchControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private DispatchOrchestrator orchestrator;

    private final UUID incidentId = UUID.randomUUID();
    private final String responderId = UUID.randomUUID().toString();

    @Test
    @DisplayName("POST /api/dispatch/{id}/accept — 200 OK")
    void accept_success() throws Exception {
        when(orchestrator.acceptDispatch(incidentId, responderId))
                .thenReturn(DispatchAckResponse.builder()
                        .incidentId(incidentId.toString())
                        .status("ACCEPTED").build());

        mockMvc.perform(post("/api/dispatch/{id}/accept", incidentId)
                        .header("X-User-Id", responderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    @DisplayName("POST /api/dispatch/{id}/reject — 200 OK, triggers escalation")
    void reject_success() throws Exception {
        when(orchestrator.rejectDispatch(incidentId, responderId))
                .thenReturn(DispatchAckResponse.builder()
                        .incidentId(incidentId.toString())
                        .status("REJECTED").build());

        mockMvc.perform(post("/api/dispatch/{id}/reject", incidentId)
                        .header("X-User-Id", responderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    @DisplayName("POST /api/dispatch/{id}/status — ARRIVED returns 200")
    void status_arrived() throws Exception {
        UpdateIncidentStatusRequest request = UpdateIncidentStatusRequest.builder()
                .status(IncidentStatus.ARRIVED).build();

        when(orchestrator.updateIncidentStatus(eq(incidentId), eq(IncidentStatus.ARRIVED), any()))
                .thenReturn(DispatchAckResponse.builder()
                        .incidentId(incidentId.toString())
                        .status("ARRIVED").build());

        mockMvc.perform(post("/api/dispatch/{id}/status", incidentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"));
    }

    @Test
    @DisplayName("POST /api/dispatch/{id}/accept — 404 when incident not found")
    void accept_notFound() throws Exception {
        when(orchestrator.acceptDispatch(incidentId, responderId))
                .thenThrow(new IncidentNotFoundException("Not found"));

        mockMvc.perform(post("/api/dispatch/{id}/accept", incidentId)
                        .header("X-User-Id", responderId))
                .andExpect(status().isNotFound());
    }
}
