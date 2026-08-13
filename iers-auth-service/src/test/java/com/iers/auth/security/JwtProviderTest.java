package com.iers.auth.security;

import com.iers.auth.entity.User;
import com.iers.auth.entity.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class JwtProviderTest {

    private JwtProvider jwtProvider;
    private User testUser;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider();
        ReflectionTestUtils.setField(jwtProvider, "jwtSecret",
                "test-secret-key-that-is-at-least-32-characters-long-for-hmac");
        ReflectionTestUtils.setField(jwtProvider, "accessTokenExpiry",
                java.time.Duration.ofMinutes(15));
        ReflectionTestUtils.setField(jwtProvider, "refreshTokenExpiry",
                java.time.Duration.ofDays(7));
        jwtProvider.init();

        testUser = User.builder()
                .id(UUID.randomUUID())
                .email("driver@test.com")
                .fullName("Test Driver")
                .role(Role.DRIVER)
                .build();
    }

    @Test
    @DisplayName("generateAccessToken — contains correct claims")
    void generateAccessToken_containsCorrectClaims() {
        String token = jwtProvider.generateAccessToken(testUser);

        assertThat(token).isNotBlank();

        Claims claims = jwtProvider.validateToken(token);
        assertThat(claims.getSubject()).isEqualTo(testUser.getId().toString());
        assertThat(claims.get("email", String.class)).isEqualTo("driver@test.com");
        assertThat(claims.get("role", String.class)).isEqualTo("DRIVER");
        assertThat(claims.get("name", String.class)).isEqualTo("Test Driver");
        assertThat(claims.get("type", String.class)).isEqualTo("ACCESS");
        assertThat(claims.getId()).isNotBlank(); // jti present
    }

    @Test
    @DisplayName("generateRefreshToken — contains minimal claims with REFRESH type")
    void generateRefreshToken_containsMinimalClaims() {
        String token = jwtProvider.generateRefreshToken(testUser);

        Claims claims = jwtProvider.validateToken(token);
        assertThat(claims.getSubject()).isEqualTo(testUser.getId().toString());
        assertThat(claims.get("type", String.class)).isEqualTo("REFRESH");
        assertThat(claims.get("email")).isNull(); // refresh tokens carry minimal data
    }

    @Test
    @DisplayName("validateToken — rejects tampered token")
    void validateToken_rejectsTamperedToken() {
        String token = jwtProvider.generateAccessToken(testUser);
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";

        assertThatThrownBy(() -> jwtProvider.validateToken(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("validateToken — rejects token signed with different key")
    void validateToken_rejectsDifferentKey() {
        JwtProvider otherProvider = new JwtProvider();
        ReflectionTestUtils.setField(otherProvider, "jwtSecret",
                "completely-different-secret-key-that-is-also-32-chars-minimum");
        ReflectionTestUtils.setField(otherProvider, "accessTokenExpiry",
                java.time.Duration.ofMinutes(15));
        ReflectionTestUtils.setField(otherProvider, "refreshTokenExpiry",
                java.time.Duration.ofDays(7));
        otherProvider.init();

        String foreignToken = otherProvider.generateAccessToken(testUser);

        assertThatThrownBy(() -> jwtProvider.validateToken(foreignToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("getRemainingTtlSeconds — returns positive value for fresh token")
    void getRemainingTtl_returnsPositive() {
        String token = jwtProvider.generateAccessToken(testUser);
        long ttl = jwtProvider.getRemainingTtlSeconds(token);
        assertThat(ttl).isGreaterThan(0).isLessThanOrEqualTo(15 * 60);
    }

    @Test
    @DisplayName("extractSubject — returns user ID")
    void extractSubject_returnsUserId() {
        String token = jwtProvider.generateAccessToken(testUser);
        String subject = jwtProvider.extractSubject(token);
        assertThat(subject).isEqualTo(testUser.getId().toString());
    }

    @Test
    @DisplayName("access and refresh tokens have different jti values")
    void accessAndRefreshTokens_haveDifferentJti() {
        String access = jwtProvider.generateAccessToken(testUser);
        String refresh = jwtProvider.generateRefreshToken(testUser);

        Claims accessClaims = jwtProvider.validateToken(access);
        Claims refreshClaims = jwtProvider.validateToken(refresh);

        assertThat(accessClaims.getId()).isNotEqualTo(refreshClaims.getId());
    }
}
