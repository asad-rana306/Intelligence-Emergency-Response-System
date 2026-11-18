package com.iers.auth.controller;

import com.iers.auth.exception.GlobalExceptionHandler;
import com.iers.auth.service.PushTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PushTokenController.class)
@Import(GlobalExceptionHandler.class)
class PushTokenControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private PushTokenService pushTokenService;

    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("POST /api/push/tokens — 204 and the token is stored for the caller")
    void register_success() throws Exception {
        mockMvc.perform(post("/api/push/tokens")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-abc\",\"platform\":\"android\"}"))
                .andExpect(status().isNoContent());

        verify(pushTokenService).register(userId, "fcm-abc", "android");
    }

    @Test
    @DisplayName("POST /api/push/tokens — 400 when the token is blank")
    void register_blankToken() throws Exception {
        mockMvc.perform(post("/api/push/tokens")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\" \"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(pushTokenService);
    }

    @Test
    @DisplayName("POST /api/push/tokens — 400 when the user header is not an id")
    void register_badUserHeader() throws Exception {
        mockMvc.perform(post("/api/push/tokens")
                        .header("X-User-Id", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-abc\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/push/tokens — 400 (not 500) when the user header is missing")
    void register_missingUserHeader() throws Exception {
        mockMvc.perform(post("/api/push/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-abc\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /api/push/tokens?token= — 204 and the token is removed")
    void unregister_success() throws Exception {
        mockMvc.perform(delete("/api/push/tokens")
                        .header("X-User-Id", userId.toString())
                        .param("token", "fcm-abc"))
                .andExpect(status().isNoContent());

        verify(pushTokenService).unregister(userId, "fcm-abc");
    }

    @Test
    @DisplayName("DELETE /api/push/tokens — 400 when the token parameter is missing")
    void unregister_missingToken() throws Exception {
        mockMvc.perform(delete("/api/push/tokens")
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isBadRequest());
    }
}
