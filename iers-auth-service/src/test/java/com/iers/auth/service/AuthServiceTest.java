package com.iers.auth.service;

import com.iers.auth.dto.request.LoginRequest;
import com.iers.auth.dto.request.RefreshTokenRequest;
import com.iers.auth.dto.request.RegisterRequest;
import com.iers.auth.dto.response.AuthResponse;
import com.iers.auth.entity.User;
import com.iers.auth.entity.enums.Role;
import com.iers.auth.exception.DuplicateResourceException;
import com.iers.auth.exception.UnauthorizedException;
import com.iers.auth.repository.ResponderProfileRepository;
import com.iers.auth.repository.UserRepository;
import com.iers.auth.security.JwtProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ResponderProfileRepository responderProfileRepository;
    @Mock private JwtProvider jwtProvider;
    @Mock private BCryptPasswordEncoder passwordEncoder;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks private AuthService authService;

    private User testUser;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(userId)
                .email("driver@test.com")
                .passwordHash("$2a$12$hashedpassword")
                .fullName("Test Driver")
                .phone("+1234567890")
                .role(Role.DRIVER)
                .build();
    }

    // ══════════════════ REGISTER ══════════════════

    @Test
    @DisplayName("register — success for DRIVER role")
    void register_success_driver() {
        RegisterRequest request = RegisterRequest.builder()
                .email("new@test.com").password("password123")
                .fullName("New User").phone("+1234567890").role(Role.DRIVER).build();

        when(userRepository.existsByEmail("new@test.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("$2a$12$encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        when(jwtProvider.generateAccessToken(any())).thenReturn("access.token.here");
        when(jwtProvider.generateRefreshToken(any())).thenReturn("refresh.token.here");
        when(jwtProvider.getAccessTokenExpiry()).thenReturn(Duration.ofMinutes(15));

        AuthResponse response = authService.register(request);

        assertThat(response.getAccessToken()).isEqualTo("access.token.here");
        assertThat(response.getRefreshToken()).isEqualTo("refresh.token.here");
        assertThat(response.getRole()).isEqualTo(Role.DRIVER);
        verify(responderProfileRepository, never()).save(any()); // No responder profile for DRIVER
    }

    @Test
    @DisplayName("register — success for RESPONDER creates ResponderProfile")
    void register_success_responder() {
        RegisterRequest request = RegisterRequest.builder()
                .email("resp@test.com").password("password123")
                .fullName("Responder").phone("+1234567890")
                .role(Role.RESPONDER).vehicleId("AMB-001").build();

        when(userRepository.existsByEmail("resp@test.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("$2a$12$encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        when(jwtProvider.generateAccessToken(any())).thenReturn("at");
        when(jwtProvider.generateRefreshToken(any())).thenReturn("rt");
        when(jwtProvider.getAccessTokenExpiry()).thenReturn(Duration.ofMinutes(15));

        authService.register(request);

        verify(responderProfileRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("register — throws DuplicateResourceException for existing email")
    void register_duplicateEmail() {
        RegisterRequest request = RegisterRequest.builder()
                .email("driver@test.com").password("pw").fullName("X")
                .phone("123").role(Role.DRIVER).build();

        when(userRepository.existsByEmail("driver@test.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("already registered");
    }

    // ══════════════════ LOGIN ══════════════════

    @Test
    @DisplayName("login — success returns tokens")
    void login_success() {
        LoginRequest request = LoginRequest.builder()
                .email("driver@test.com").password("correctPassword").build();

        when(userRepository.findByEmail("driver@test.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("correctPassword", testUser.getPasswordHash())).thenReturn(true);
        when(jwtProvider.generateAccessToken(testUser)).thenReturn("access");
        when(jwtProvider.generateRefreshToken(testUser)).thenReturn("refresh");
        when(jwtProvider.getAccessTokenExpiry()).thenReturn(Duration.ofMinutes(15));
        when(userRepository.save(any())).thenReturn(testUser);

        AuthResponse response = authService.login(request);

        assertThat(response.getAccessToken()).isEqualTo("access");
        assertThat(response.getEmail()).isEqualTo("driver@test.com");
    }

    @Test
    @DisplayName("login — wrong password throws UnauthorizedException")
    void login_wrongPassword() {
        LoginRequest request = LoginRequest.builder()
                .email("driver@test.com").password("wrongPassword").build();

        when(userRepository.findByEmail("driver@test.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("wrongPassword", testUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    @DisplayName("login — unknown email throws UnauthorizedException")
    void login_unknownEmail() {
        LoginRequest request = LoginRequest.builder()
                .email("unknown@test.com").password("pw").build();

        when(userRepository.findByEmail("unknown@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("login — updates deviceId when provided")
    void login_updatesDeviceId() {
        LoginRequest request = LoginRequest.builder()
                .email("driver@test.com").password("correct")
                .deviceId("fcm-token-abc").build();

        when(userRepository.findByEmail("driver@test.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("correct", testUser.getPasswordHash())).thenReturn(true);
        when(jwtProvider.generateAccessToken(any())).thenReturn("at");
        when(jwtProvider.generateRefreshToken(any())).thenReturn("rt");
        when(jwtProvider.getAccessTokenExpiry()).thenReturn(Duration.ofMinutes(15));
        when(userRepository.save(any())).thenReturn(testUser);

        authService.login(request);

        assertThat(testUser.getDeviceId()).isEqualTo("fcm-token-abc");
    }

    // ══════════════════ REFRESH ══════════════════

    @Test
    @DisplayName("refresh — success with valid refresh token and matching hash")
    void refresh_success() {
        String refreshToken = "valid.refresh.token";
        String tokenHash = AuthService.sha256(refreshToken);
        testUser.setRefreshTokenHash(tokenHash);

        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken(refreshToken).build();

        Claims claims = new DefaultClaims(Map.of(
                "sub", userId.toString(), "type", "REFRESH"));

        when(jwtProvider.validateToken(refreshToken)).thenReturn(claims);
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(jwtProvider.generateAccessToken(testUser)).thenReturn("new-access");
        when(jwtProvider.generateRefreshToken(testUser)).thenReturn("new-refresh");
        when(jwtProvider.getAccessTokenExpiry()).thenReturn(Duration.ofMinutes(15));
        when(userRepository.save(any())).thenReturn(testUser);

        AuthResponse response = authService.refresh(request);

        assertThat(response.getAccessToken()).isEqualTo("new-access");
    }

    @Test
    @DisplayName("refresh — throws when token type is not REFRESH")
    void refresh_wrongTokenType() {
        Claims claims = new DefaultClaims(Map.of(
                "sub", userId.toString(), "type", "ACCESS"));

        when(jwtProvider.validateToken("access-token")).thenReturn(claims);

        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken("access-token").build();

        assertThatThrownBy(() -> authService.refresh(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("not a refresh token");
    }

    @Test
    @DisplayName("refresh — throws when hash doesn't match (token already rotated)")
    void refresh_hashMismatch() {
        testUser.setRefreshTokenHash("old-hash-that-wont-match");

        Claims claims = new DefaultClaims(Map.of(
                "sub", userId.toString(), "type", "REFRESH"));

        when(jwtProvider.validateToken("stale-token")).thenReturn(claims);
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken("stale-token").build();

        assertThatThrownBy(() -> authService.refresh(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("revoked or rotated");
    }

    // ══════════════════ LOGOUT ══════════════════

    @Test
    @DisplayName("logout — blocklists token in Redis and clears refresh hash")
    void logout_success() {
        String accessToken = "access.token.value";
        Claims claims = new DefaultClaims(Map.of("jti", "token-id-123"));

        when(jwtProvider.validateToken(accessToken)).thenReturn(claims);
        when(jwtProvider.getRemainingTtlSeconds(accessToken)).thenReturn(300L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any())).thenReturn(testUser);

        authService.logout(accessToken, userId.toString());

        verify(valueOperations).set(eq("token:blocklist:token-id-123"), eq("revoked"),
                eq(300L), any());
        assertThat(testUser.getRefreshTokenHash()).isNull();
    }

    @Test
    @DisplayName("logout — handles null access token gracefully")
    void logout_nullToken() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any())).thenReturn(testUser);

        assertThatCode(() -> authService.logout(null, userId.toString()))
                .doesNotThrowAnyException();
    }
}
