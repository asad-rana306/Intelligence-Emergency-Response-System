package com.iers.auth.controller;

import com.iers.auth.dto.request.LoginRequest;
import com.iers.auth.dto.request.RefreshTokenRequest;
import com.iers.auth.dto.request.RegisterRequest;
import com.iers.auth.dto.response.AuthResponse;
import com.iers.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refresh(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Logout: blocklists the current access token and invalidates the refresh token.
     * The access token is extracted from the Authorization header (passed by the gateway
     * before stripping). The user ID comes from the gateway's X-User-Id header.
     *
     * NOTE: The gateway strips the Authorization header and injects X-User-Id.
     * For logout, we need the raw token. The gateway should forward the token
     * for this specific endpoint, OR the client sends it in the request body.
     * For simplicity, the client sends the access token in the request header.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader("X-User-Id") String userId) {

        String accessToken = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            accessToken = authHeader.substring(7);
        }
        authService.logout(accessToken, userId);
        return ResponseEntity.noContent().build();
    }
}
