package com.iers.dispatch.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.dispatch.dto.Viewer;
import com.iers.dispatch.dto.request.UpdateIncidentStatusRequest;
import com.iers.dispatch.dto.response.DispatchAckResponse;
import com.iers.dispatch.dto.response.IncidentResponse;
import com.iers.dispatch.entity.enums.IncidentStatus;
import com.iers.dispatch.exception.GlobalExceptionHandler;
import com.iers.dispatch.exception.IncidentAccessDeniedException;
import com.iers.dispatch.exception.IncidentNotFoundException;
import com.iers.dispatch.exception.InvalidIncidentStateException;
import com.iers.dispatch.service.DispatchOrchestrator;
import com.iers.dispatch.service.IncidentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DispatchController.class)
@Import(GlobalExceptionHandler.class)
class DispatchControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private DispatchOrchestrator orchestrator;
    @MockBean private IncidentService incidentService;

    private final UUID incidentId = UUID.randomUUID();
    private final UUID responderId = UUID.randomUUID();

    private DispatchAckResponse ack(String status) {
        return DispatchAckResponse.builder().incidentId(incidentId.toString()).status(status).build();
    }

    // ── accept ──

    @Test
    @DisplayName("POST /accept — 200 OK")
    void accept_success() throws Exception {
        when(orchestrator.acceptDispatch(incidentId, responderId)).thenReturn(ack("ACCEPTED"));

        mockMvc.perform(post("/api/dispatch/{id}/accept", incidentId)
                        .header("X-User-Id", responderId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.incidentId").value(incidentId.toString()));
    }

    @Test
    @DisplayName("POST /accept — 403 when the incident was not offered to this responder")
    void accept_wrongPerson() throws Exception {
        when(orchestrator.acceptDispatch(incidentId, responderId))
                .thenThrow(new IncidentAccessDeniedException("This incident was not offered to you"));

        mockMvc.perform(post("/api/dispatch/{id}/accept", incidentId)
                        .header("X-User-Id", responderId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("This incident was not offered to you"))
                .andExpect(jsonPath("$.path").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("POST /accept — 409 when the incident is in the wrong state")
    void accept_wrongState() throws Exception {
        when(orchestrator.acceptDispatch(incidentId, responderId))
                .thenThrow(new InvalidIncidentStateException("Incident is CANCELLED_BY_USER"));

        mockMvc.perform(post("/api/dispatch/{id}/accept", incidentId)
                        .header("X-User-Id", responderId.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("POST /accept — 404 when the incident is unknown")
    void accept_notFound() throws Exception {
        when(orchestrator.acceptDispatch(incidentId, responderId))
                .thenThrow(new IncidentNotFoundException("Not found"));

        mockMvc.perform(post("/api/dispatch/{id}/accept", incidentId)
                        .header("X-User-Id", responderId.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /accept — 400 for an ID that is not a UUID")
    void accept_badIncidentId() throws Exception {
        mockMvc.perform(post("/api/dispatch/{id}/accept", "1")
                        .header("X-User-Id", responderId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("UUID")));
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("POST /accept — 400 when X-User-Id is not a UUID")
    void accept_badUserId() throws Exception {
        mockMvc.perform(post("/api/dispatch/{id}/accept", incidentId)
                        .header("X-User-Id", "not-a-uuid"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("POST /accept — 400 when X-User-Id is missing")
    void accept_missingUser() throws Exception {
        mockMvc.perform(post("/api/dispatch/{id}/accept", incidentId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("X-User-Id")));
    }

    // ── reject ──

    @Test
    @DisplayName("POST /reject — 200 OK")
    void reject_success() throws Exception {
        when(orchestrator.rejectDispatch(incidentId, responderId)).thenReturn(ack("REJECTED"));

        mockMvc.perform(post("/api/dispatch/{id}/reject", incidentId)
                        .header("X-User-Id", responderId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    @DisplayName("POST /reject — 403 and 409 are passed through")
    void reject_errors() throws Exception {
        when(orchestrator.rejectDispatch(incidentId, responderId))
                .thenThrow(new IncidentAccessDeniedException("no"))
                .thenThrow(new InvalidIncidentStateException("late"));

        mockMvc.perform(post("/api/dispatch/{id}/reject", incidentId)
                        .header("X-User-Id", responderId.toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/dispatch/{id}/reject", incidentId)
                        .header("X-User-Id", responderId.toString()))
                .andExpect(status().isConflict());
    }

    // ── status ──

    @Test
    @DisplayName("POST /status — ARRIVED returns 200")
    void status_arrived() throws Exception {
        UpdateIncidentStatusRequest request = UpdateIncidentStatusRequest.builder()
                .status(IncidentStatus.ARRIVED).build();
        when(orchestrator.updateIncidentStatus(eq(incidentId), eq(responderId), eq(IncidentStatus.ARRIVED), any()))
                .thenReturn(ack("ARRIVED"));

        mockMvc.perform(post("/api/dispatch/{id}/status", incidentId)
                        .header("X-User-Id", responderId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"));
    }

    @Test
    @DisplayName("POST /status — hospitalId reaches the service")
    void status_hospital() throws Exception {
        UpdateIncidentStatusRequest request = UpdateIncidentStatusRequest.builder()
                .status(IncidentStatus.EN_ROUTE_HOSPITAL).hospitalId("HOSP-1").build();
        when(orchestrator.updateIncidentStatus(incidentId, responderId, IncidentStatus.EN_ROUTE_HOSPITAL, "HOSP-1"))
                .thenReturn(ack("EN_ROUTE_HOSPITAL"));

        mockMvc.perform(post("/api/dispatch/{id}/status", incidentId)
                        .header("X-User-Id", responderId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /status — 403 and 409 are passed through")
    void status_errors() throws Exception {
        when(orchestrator.updateIncidentStatus(eq(incidentId), eq(responderId), any(), any()))
                .thenThrow(new IncidentAccessDeniedException("not yours"))
                .thenThrow(new InvalidIncidentStateException("wrong order"));
        String body = "{\"status\":\"RESOLVED\"}";

        mockMvc.perform(post("/api/dispatch/{id}/status", incidentId)
                        .header("X-User-Id", responderId.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/dispatch/{id}/status", incidentId)
                        .header("X-User-Id", responderId.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /status — 400 for a status value that does not exist")
    void status_unknownValue() throws Exception {
        mockMvc.perform(post("/api/dispatch/{id}/status", incidentId)
                        .header("X-User-Id", responderId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FLYING\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("POST /status — 400 when the status is missing")
    void status_missing() throws Exception {
        mockMvc.perform(post("/api/dispatch/{id}/status", incidentId)
                        .header("X-User-Id", responderId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("status")));
    }

    @Test
    @DisplayName("POST /status — 400 when there is no body at all")
    void status_noBody() throws Exception {
        mockMvc.perform(post("/api/dispatch/{id}/status", incidentId)
                        .header("X-User-Id", responderId.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /status — 400 when the service rejects the value")
    void status_serviceSaysBadValue() throws Exception {
        when(orchestrator.updateIncidentStatus(eq(incidentId), eq(responderId), eq(IncidentStatus.ACCEPTED), any()))
                .thenThrow(new IllegalArgumentException("Status ACCEPTED cannot be set here"));

        mockMvc.perform(post("/api/dispatch/{id}/status", incidentId)
                        .header("X-User-Id", responderId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Status ACCEPTED cannot be set here"));
    }

    // ── current offer ──

    @Test
    @DisplayName("GET /offers/current — 200 with the open offer")
    void currentOffer_present() throws Exception {
        IncidentResponse offer = IncidentResponse.builder()
                .id(incidentId.toString()).crashEventId(UUID.randomUUID().toString())
                .status(IncidentStatus.DISPATCHED).driverName("Ali")
                .dispatchExpiresAt(Instant.parse("2026-10-29T18:30:42Z"))
                .serverTime(Instant.parse("2026-10-29T18:30:12Z")).build();
        when(incidentService.getCurrentOffer(any(Viewer.class))).thenReturn(Optional.of(offer));

        mockMvc.perform(get("/api/dispatch/offers/current")
                        .header("X-User-Id", responderId.toString())
                        .header("X-User-Role", "RESPONDER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPATCHED"))
                .andExpect(jsonPath("$.dispatchExpiresAt").value("2026-10-29T18:30:42Z"))
                .andExpect(jsonPath("$.serverTime").value("2026-10-29T18:30:12Z"));
    }

    @Test
    @DisplayName("GET /offers/current — 204 when nothing is waiting")
    void currentOffer_none() throws Exception {
        when(incidentService.getCurrentOffer(any(Viewer.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/dispatch/offers/current")
                        .header("X-User-Id", responderId.toString()))
                .andExpect(status().isNoContent());
    }
}
