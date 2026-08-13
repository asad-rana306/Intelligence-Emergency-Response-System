package com.iers.auth.security;

import com.iers.auth.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Generates JWTs for the IERS system.
 *
 * Access tokens are short-lived (15 min) and carry user identity + role.
 * Refresh tokens are long-lived (7 days) and carry only the user ID + a jti for rotation.
 *
 * The signing key MUST match the one configured in the API Gateway.
 */
@Slf4j
@Component
public class JwtProvider {

    @Value("${auth.jwt.secret}")
    private String jwtSecret;

    @Value("${auth.jwt.access-token-expiry:PT15M}")
    private Duration accessTokenExpiry;

    @Value("${auth.jwt.refresh-token-expiry:P7D}")
    private Duration refreshTokenExpiry;

    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        log.info("JWT provider initialized — access TTL={}, refresh TTL={}",
                accessTokenExpiry, refreshTokenExpiry);
    }

    /**
     * Generate a short-lived access token with full user identity.
     */
    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())       // jti — for Redis blocklisting
                .subject(user.getId().toString())        // sub — user UUID
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("name", user.getFullName())
                .claim("type", "ACCESS")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenExpiry)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Generate a long-lived refresh token (minimal claims).
     */
    public String generateRefreshToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim("type", "REFRESH")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTokenExpiry)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parse and validate any token (access or refresh).
     *
     * @throws JwtException if the token is invalid, expired, or tampered with
     */
    public Claims validateToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Extract the subject (user ID) from a token without full validation.
     * Used for logging and diagnostics only.
     */
    public String extractSubject(String token) {
        return validateToken(token).getSubject();
    }

    /**
     * Get the remaining TTL of a token in seconds.
     * Used when blocklisting a token — the Redis key TTL should match.
     */
    public long getRemainingTtlSeconds(String token) {
        Claims claims = validateToken(token);
        Instant expiry = claims.getExpiration().toInstant();
        long remaining = Duration.between(Instant.now(), expiry).getSeconds();
        return Math.max(remaining, 0);
    }

    public Duration getAccessTokenExpiry() {
        return accessTokenExpiry;
    }
}
