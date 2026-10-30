package com.iers.dispatch.controller;

import com.iers.dispatch.dto.Viewer;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IncidentController.class)
@Import(GlobalExceptionHandler.class)
class IncidentControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private IncidentService incidentService;
    @MockBean private DispatchOrchestrator orchestrator;

    private final UUID incidentId = UUID.randomUUID();
    private final UUID crashId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private IncidentResponse response() {
        return IncidentResponse.builder()
                .id(incidentId.toString()).crashEventId(crashId.toString())
                .driverId(userId.toString()).driverName("Ali")
                .status(IncidentStatus.DISPATCHING)
                .createdAt(Instant.parse("2026-10-29T18:30:10Z"))
                .serverTime(Instant.parse("2026-10-29T18:30:40Z")).build();
    }

    // ── GET /{id} ──

    @Test
    @DisplayName("GET /{id} — 200 with the new fields, and the role header reaches the service")
    void getById_ok() throws Exception {
        when(incidentService.getById(eq(incidentId), any(Viewer.class))).thenReturn(response());

        mockMvc.perform(get("/api/incidents/{id}", incidentId)
                        .header("X-User-Id", userId.toString())
                        .header("X-User-Role", "DRIVER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incidentId.toString()))
                .andExpect(jsonPath("$.driverId").value(userId.toString()))
                .andExpect(jsonPath("$.serverTime").value("2026-10-29T18:30:40Z"))
                .andExpect(jsonPath("$.responder").doesNotExist())   // null until accepted
                .andExpect(jsonPath("$.dispatchExpiresAt").doesNotExist());

        ArgumentCaptor<Viewer> viewer = ArgumentCaptor.forClass(Viewer.class);
        verify(incidentService).getById(eq(incidentId), viewer.capture());
        assertThat(viewer.getValue().id()).isEqualTo(userId);
        assertThat(viewer.getValue().role()).isEqualTo("DRIVER");
    }

    @Test
    @DisplayName("GET /{id} — 403 for someone else's incident")
    void getById_forbidden() throws Exception {
        when(incidentService.getById(eq(incidentId), any(Viewer.class)))
                .thenThrow(new IncidentAccessDeniedException("This incident does not belong to you"));

        mockMvc.perform(get("/api/incidents/{id}", incidentId).header("X-User-Id", userId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET /{id} — 404 for an unknown incident")
    void getById_notFound() throws Exception {
        when(incidentService.getById(eq(incidentId), any(Viewer.class)))
                .thenThrow(new IncidentNotFoundException("Incident not found"));

        mockMvc.perform(get("/api/incidents/{id}", incidentId).header("X-User-Id", userId.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /{id} — 400 when the ID is not a UUID")
    void getById_badId() throws Exception {
        mockMvc.perform(get("/api/incidents/{id}", "abc").header("X-User-Id", userId.toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(incidentService);
    }

    @Test
    @DisplayName("GET /{id} — 400 when X-User-Id is missing")
    void getById_noUser() throws Exception {
        mockMvc.perform(get("/api/incidents/{id}", incidentId))
                .andExpect(status().isBadRequest());
    }

    // ── /active ──

    @Test
    @DisplayName("GET /active — 200 with a list")
    void active() throws Exception {
        when(incidentService.getActiveIncidents(any(Viewer.class))).thenReturn(List.of(response()));

        mockMvc.perform(get("/api/incidents/active")
                        .header("X-User-Id", userId.toString()).header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(incidentId.toString()));
    }

    @Test
    @DisplayName("GET /active — a missing role header is treated as UNKNOWN, not an error")
    void active_noRole() throws Exception {
        when(incidentService.getActiveIncidents(any(Viewer.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/incidents/active").header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        ArgumentCaptor<Viewer> viewer = ArgumentCaptor.forClass(Viewer.class);
        verify(incidentService).getActiveIncidents(viewer.capture());
        assertThat(viewer.getValue().role()).isEqualTo("UNKNOWN");
    }

    // ── /mine ──

    @Test
    @DisplayName("GET /mine — 200 when I have an incident")
    void mine_present() throws Exception {
        when(incidentService.getMine(any(Viewer.class))).thenReturn(Optional.of(response()));

        mockMvc.perform(get("/api/incidents/mine")
                        .header("X-User-Id", userId.toString()).header("X-User-Role", "DRIVER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crashEventId").value(crashId.toString()));
    }

    @Test
    @DisplayName("GET /mine — 204 when I have none")
    void mine_none() throws Exception {
        when(incidentService.getMine(any(Viewer.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/incidents/mine")
                        .header("X-User-Id", userId.toString()).header("X-User-Role", "DRIVER"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET /mine — 403 for roles that have no personal incident")
    void mine_forbidden() throws Exception {
        when(incidentService.getMine(any(Viewer.class)))
                .thenThrow(new IncidentAccessDeniedException("Only drivers and responders have a personal incident"));

        mockMvc.perform(get("/api/incidents/mine")
                        .header("X-User-Id", userId.toString()).header("X-User-Role", "ADMIN"))
                .andExpect(status().isForbidden());
    }

    // ── /by-crash ──

    @Test
    @DisplayName("GET /by-crash/{id} — 200 for the owner")
    void byCrash_ok() throws Exception {
        when(incidentService.getByCrash(eq(crashId), any(Viewer.class))).thenReturn(response());

        mockMvc.perform(get("/api/incidents/by-crash/{id}", crashId)
                        .header("X-User-Id", userId.toString()).header("X-User-Role", "DRIVER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incidentId.toString()));
    }

    @Test
    @DisplayName("GET /by-crash/{id} — 404 until the incident exists, 403 for another driver")
    void byCrash_errors() throws Exception {
        when(incidentService.getByCrash(eq(crashId), any(Viewer.class)))
                .thenThrow(new IncidentNotFoundException("No incident yet"))
                .thenThrow(new IncidentAccessDeniedException("Not yours"));

        mockMvc.perform(get("/api/incidents/by-crash/{id}", crashId).header("X-User-Id", userId.toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/incidents/by-crash/{id}", crashId).header("X-User-Id", userId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /by-crash/{id} — 400 for a bad crash ID")
    void byCrash_badId() throws Exception {
        mockMvc.perform(get("/api/incidents/by-crash/{id}", "1").header("X-User-Id", userId.toString()))
                .andExpect(status().isBadRequest());
    }

    // ── bystander ──

    @Test
    @DisplayName("POST /bystander-assist — 200")
    void bystander_ok() throws Exception {
        when(orchestrator.markBystanderAssisting(eq(incidentId), any(Viewer.class)))
                .thenReturn(DispatchAckResponse.builder().incidentId(incidentId.toString())
                        .status("BYSTANDER_NOTIFIED").message("Dispatchers notified.").build());

        mockMvc.perform(post("/api/incidents/{id}/bystander-assist", incidentId)
                        .header("X-User-Id", userId.toString()).header("X-User-Role", "DRIVER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BYSTANDER_NOTIFIED"));
    }

    @Test
    @DisplayName("POST /bystander-assist — 403 for a stranger, 409 for a finished incident")
    void bystander_errors() throws Exception {
        when(orchestrator.markBystanderAssisting(eq(incidentId), any(Viewer.class)))
                .thenThrow(new IncidentAccessDeniedException("no"))
                .thenThrow(new InvalidIncidentStateException("done"));

        mockMvc.perform(post("/api/incidents/{id}/bystander-assist", incidentId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/incidents/{id}/bystander-assist", incidentId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isConflict());
    }
}
