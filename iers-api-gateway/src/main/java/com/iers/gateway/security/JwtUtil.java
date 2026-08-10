package com.iers.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Edge-only JWT utility. This class ONLY validates tokens (signature + expiry).
 * Token GENERATION lives in Auth Service (Service 2).
 *
 * The same HMAC secret must be shared between this gateway and the Auth Service
 * via the JWT_SECRET environment variable.
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${gateway.jwt.secret}")
    private String jwtSecret;

    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            log.warn("JWT_SECRET is not configured — JWT validation will reject all tokens");
            return;
        }
        // Derive HMAC-SHA key from the raw secret string
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        log.info("JWT signing key initialized successfully");
    }

    /**
     * Parse and validate the token. Returns claims on success.
     *
     * @throws JwtException       if the signature is invalid or the token is malformed
     * @throws ExpiredJwtException if the token has expired
     * @throws IllegalStateException if no signing key is configured
     */
    public Claims validateToken(String token) {
        if (signingKey == null) {
            throw new IllegalStateException("JWT signing key is not configured");
        }
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Safely attempt validation without throwing.
     * Returns null if the token is invalid for any reason.
     */
    public Claims validateTokenSilently(String token) {
        try {
            return validateToken(token);
        } catch (Exception e) {
            log.debug("Token validation failed: {}", e.getMessage());
            return null;
        }
    }
}
