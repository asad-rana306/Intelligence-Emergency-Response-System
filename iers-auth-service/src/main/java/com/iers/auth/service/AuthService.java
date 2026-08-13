package com.iers.auth.service;

import com.iers.auth.dto.request.LoginRequest;
import com.iers.auth.dto.request.RefreshTokenRequest;
import com.iers.auth.dto.request.RegisterRequest;
import com.iers.auth.dto.response.AuthResponse;
import com.iers.auth.entity.ResponderProfile;
import com.iers.auth.entity.User;
import com.iers.auth.entity.enums.DutyStatus;
import com.iers.auth.entity.enums.Role;
import com.iers.auth.exception.DuplicateResourceException;
import com.iers.auth.exception.UnauthorizedException;
import com.iers.auth.repository.ResponderProfileRepository;
import com.iers.auth.repository.UserRepository;
import com.iers.auth.security.JwtProvider;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final ResponderProfileRepository responderProfileRepository;
    private final JwtProvider jwtProvider;
    private final BCryptPasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;

    /**
     * Register a new user. If role is RESPONDER, also create a ResponderProfile.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email already registered: " + request.getEmail());
        }

        User user = User.builder()
                .email(request.getEmail().toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .phone(request.getPhone())
                .role(request.getRole())
                .build();

        user = userRepository.save(user);

        // Auto-create responder profile for RESPONDER role
        if (request.getRole() == Role.RESPONDER) {
            ResponderProfile profile = ResponderProfile.builder()
                    .user(user)
                    .dutyStatus(DutyStatus.OFF_DUTY)
                    .vehicleId(request.getVehicleId())
                    .build();
            responderProfileRepository.save(profile);
        }

        return generateAuthResponse(user);
    }

    /**
     * Authenticate with email + password.
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail().toLowerCase().trim())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        // Update device ID for push notifications if provided
        if (request.getDeviceId() != null) {
            user.setDeviceId(request.getDeviceId());
            userRepository.save(user);
        }

        return generateAuthResponse(user);
    }

    /**
     * Rotate refresh token: validate the old one, issue a new pair.
     */
    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        Claims claims;
        try {
            claims = jwtProvider.validateToken(request.getRefreshToken());
        } catch (Exception e) {
            throw new UnauthorizedException("Invalid or expired refresh token");
        }

        // Verify it's actually a refresh token
        if (!"REFRESH".equals(claims.get("type", String.class))) {
            throw new UnauthorizedException("Token is not a refresh token");
        }

        UUID userId = UUID.fromString(claims.getSubject());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("User not found"));

        // Verify the refresh token hash matches what's stored
        String tokenHash = sha256(request.getRefreshToken());
        if (user.getRefreshTokenHash() == null || !user.getRefreshTokenHash().equals(tokenHash)) {
            throw new UnauthorizedException("Refresh token has been revoked or rotated");
        }

        return generateAuthResponse(user);
    }

    /**
     * Logout: blocklist the access token in Redis and clear the refresh token hash.
     */
    @Transactional
    public void logout(String accessToken, String userId) {
        // Blocklist the access token by its jti
        try {
            Claims claims = jwtProvider.validateToken(accessToken);
            String jti = claims.getId();
            long ttl = jwtProvider.getRemainingTtlSeconds(accessToken);
            if (jti != null && ttl > 0) {
                redisTemplate.opsForValue().set(
                        "token:blocklist:" + jti, "revoked", ttl, TimeUnit.SECONDS);
                log.info("Blocklisted token jti={} for user={}, ttl={}s", jti, userId, ttl);
            }
        } catch (Exception e) {
            log.warn("Could not blocklist access token for user={}: {}", userId, e.getMessage());
        }

        // Clear the stored refresh token hash
        UUID uid = UUID.fromString(userId);
        userRepository.findById(uid).ifPresent(user -> {
            user.setRefreshTokenHash(null);
            userRepository.save(user);
        });
    }

    // ──────────────────── Helpers ────────────────────

    private AuthResponse generateAuthResponse(User user) {
        String accessToken = jwtProvider.generateAccessToken(user);
        String refreshToken = jwtProvider.generateRefreshToken(user);

        // Store refresh token hash for rotation validation
        user.setRefreshTokenHash(sha256(refreshToken));
        userRepository.save(user);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtProvider.getAccessTokenExpiry().getSeconds())
                .userId(user.getId().toString())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
