package com.iers.auth.controller;

import com.iers.auth.dto.response.AvailableResponderResponse;
import com.iers.auth.dto.response.EmergencyContactResponse;
import com.iers.auth.dto.response.MedicalProfileResponse;
import com.iers.auth.entity.enums.DutyStatus;
import com.iers.auth.exception.GlobalExceptionHandler;
import com.iers.auth.exception.ResourceNotFoundException;
import com.iers.auth.service.EmergencyContactService;
import com.iers.auth.service.MedicalProfileService;
import com.iers.auth.service.ResponderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InternalApiController.class)
@Import(GlobalExceptionHandler.class)
class InternalApiControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private MedicalProfileService medicalProfileService;
    @MockBean private EmergencyContactService contactService;
    @MockBean private ResponderService responderService;

    private final UUID userId = UUID.randomUUID();

    // ══════════════════ MEDICAL PROFILE ══════════════════

    @Test
    @DisplayName("GET /internal/users/{id}/medical-profile — 200 OK")
    void getMedicalProfile_success() throws Exception {
        MedicalProfileResponse profile = MedicalProfileResponse.builder()
                .id(UUID.randomUUID().toString()).userId(userId.toString())
                .bloodType("AB+").allergies("None").build();

        when(medicalProfileService.getProfileByUserId(userId)).thenReturn(profile);

        mockMvc.perform(get("/internal/users/{userId}/medical-profile", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bloodType").value("AB+"));
    }

    @Test
    @DisplayName("GET /internal/users/{id}/medical-profile — 404 when not found")
    void getMedicalProfile_notFound() throws Exception {
        when(medicalProfileService.getProfileByUserId(userId))
                .thenThrow(new ResourceNotFoundException("Not found"));

        mockMvc.perform(get("/internal/users/{userId}/medical-profile", userId))
                .andExpect(status().isNotFound());
    }

    // ══════════════════ EMERGENCY CONTACTS ══════════════════

    @Test
    @DisplayName("GET /internal/users/{id}/emergency-contacts — 200 OK with list")
    void getEmergencyContacts_success() throws Exception {
        List<EmergencyContactResponse> contacts = List.of(
                EmergencyContactResponse.builder()
                        .id(UUID.randomUUID().toString())
                        .contactName("Mom").phone("+1111111111").relationship("Mother").build()
        );

        when(contactService.getContactsByUserId(userId)).thenReturn(contacts);

        mockMvc.perform(get("/internal/users/{userId}/emergency-contacts", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].contactName").value("Mom"));
    }

    // ══════════════════ AVAILABLE RESPONDERS ══════════════════

    @Test
    @DisplayName("GET /internal/responders/available — 200 OK with list")
    void getAvailableResponders_success() throws Exception {
        List<AvailableResponderResponse> responders = List.of(
                AvailableResponderResponse.builder()
                        .responderId(UUID.randomUUID().toString())
                        .userId(UUID.randomUUID().toString())
                        .fullName("Responder A").phone("+1222222222")
                        .vehicleId("AMB-001").latitude(37.7749).longitude(-122.4194).build()
        );

        when(responderService.getAvailableResponders()).thenReturn(responders);

        mockMvc.perform(get("/internal/responders/available"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].vehicleId").value("AMB-001"));
    }

    // ══════════════════ STATUS UPDATE ══════════════════

    @Test
    @DisplayName("PUT /internal/responders/{id}/status — 204 No Content")
    void updateResponderStatus_success() throws Exception {
        doNothing().when(responderService).updateStatusByUserId(any(), any());

        mockMvc.perform(put("/internal/responders/{userId}/status", userId)
                        .param("status", "ON_MISSION"))
                .andExpect(status().isNoContent());

        verify(responderService).updateStatusByUserId(eq(userId), eq(DutyStatus.ON_MISSION));
    }
}
