package com.iers.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.auth.dto.request.LoginRequest;
import com.iers.auth.dto.request.RegisterRequest;
import com.iers.auth.dto.request.RefreshTokenRequest;
import com.iers.auth.dto.response.AuthResponse;
import com.iers.auth.entity.enums.Role;
import com.iers.auth.exception.DuplicateResourceException;
import com.iers.auth.exception.GlobalExceptionHandler;
import com.iers.auth.exception.UnauthorizedException;
import com.iers.auth.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private AuthService authService;

    // ══════════════════ REGISTER ══════════════════

    @Test
    @DisplayName("POST /auth/register — 201 Created on success")
    void register_success() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email("new@test.com").password("password123")
                .fullName("New User").phone("+1234567890").role(Role.DRIVER).build();

        AuthResponse response = AuthResponse.builder()
                .accessToken("access-token").refreshToken("refresh-token")
                .tokenType("Bearer").expiresIn(900)
                .userId("uuid-123").email("new@test.com").role(Role.DRIVER).build();

        when(authService.register(any())).thenReturn(response);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.role").value("DRIVER"));
    }

    @Test
    @DisplayName("POST /auth/register — 409 Conflict for duplicate email")
    void register_duplicateEmail() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email("existing@test.com").password("password123")
                .fullName("User").phone("123").role(Role.DRIVER).build();

        when(authService.register(any()))
                .thenThrow(new DuplicateResourceException("Email already registered"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email already registered"));
    }

    @Test
    @DisplayName("POST /auth/register — 400 Bad Request for invalid input")
    void register_validationError() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email("not-an-email").password("short")
                .fullName("").phone("").role(null).build();

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ══════════════════ LOGIN ══════════════════

    @Test
    @DisplayName("POST /auth/login — 200 OK on success")
    void login_success() throws Exception {
        LoginRequest request = LoginRequest.builder()
                .email("driver@test.com").password("correct").build();

        AuthResponse response = AuthResponse.builder()
                .accessToken("at").refreshToken("rt")
                .tokenType("Bearer").expiresIn(900)
                .userId("uuid").email("driver@test.com").role(Role.DRIVER).build();

        when(authService.login(any())).thenReturn(response);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("driver@test.com"));
    }

    @Test
    @DisplayName("POST /auth/login — 401 Unauthorized for bad credentials")
    void login_unauthorized() throws Exception {
        LoginRequest request = LoginRequest.builder()
                .email("driver@test.com").password("wrong").build();

        when(authService.login(any()))
                .thenThrow(new UnauthorizedException("Invalid email or password"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // ══════════════════ REFRESH ══════════════════

    @Test
    @DisplayName("POST /auth/refresh — 200 OK with new tokens")
    void refresh_success() throws Exception {
        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken("valid-refresh").build();

        AuthResponse response = AuthResponse.builder()
                .accessToken("new-at").refreshToken("new-rt")
                .tokenType("Bearer").expiresIn(900)
                .userId("uuid").email("user@test.com").role(Role.DRIVER).build();

        when(authService.refresh(any())).thenReturn(response);

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-at"));
    }

    // ══════════════════ LOGOUT ══════════════════

    @Test
    @DisplayName("POST /auth/logout — 204 No Content")
    void logout_success() throws Exception {
        doNothing().when(authService).logout(any(), any());

        mockMvc.perform(post("/auth/logout")
                        .header("X-User-Id", "user-uuid-123")
                        .header("Authorization", "Bearer some.access.token"))
                .andExpect(status().isNoContent());

        verify(authService).logout("some.access.token", "user-uuid-123");
    }
}
