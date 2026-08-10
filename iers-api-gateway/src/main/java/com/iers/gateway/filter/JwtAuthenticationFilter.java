package com.iers.gateway.filter;

import com.iers.gateway.security.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Global filter that enforces authentication on every request passing through
 * the gateway. Three authentication modes are supported:
 *
 * 1. OPEN — No authentication required (login, register, health checks).
 * 2. DEVICE API KEY — For IoT hardware endpoints (crash ingestion, heartbeats).
 *    Validated against a static secret shared with the embedded device firmware.
 * 3. JWT BEARER — For all user-facing endpoints (phone app, responder app, dashboard).
 *    The token's signature and expiry are verified, then its jti is checked against
 *    the Redis blocklist (populated by the Auth Service on logout).
 *
 * On success, user identity is propagated downstream via X-headers so that
 * downstream services never need to re-parse the JWT.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final JwtUtil jwtUtil;
    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

    @Value("${gateway.device.api-key:}")
    private String deviceApiKey;

    // ── Paths that require NO authentication at all ──
    private static final List<String> OPEN_PATHS = List.of(
            "/auth/login",
            "/auth/register",
            "/auth/refresh",
            "/actuator/health",
            "/fallback"
    );

    // ── Paths authenticated by device API key, NOT JWT ──
    private static final List<String> DEVICE_PATHS = List.of(
            "/api/telemetry/crash",
            "/api/telemetry/heartbeat",
            "/api/telemetry/crash/sms"
    );

    /**
     * Highest precedence among custom filters so auth runs before anything else.
     */
    @Override
    public int getOrder() {
        return -1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // ── 1. Open endpoints — pass through ──
        if (isOpenPath(path)) {
            return chain.filter(exchange);
        }

        // ── 2. Device endpoints — validate API key ──
        if (isDevicePath(path)) {
            return handleDeviceAuth(exchange, chain);
        }

        // ── 3. WebSocket — token may be in query param ──
        String token = extractToken(request);
        if (token == null && path.startsWith("/ws/")) {
            token = request.getQueryParams().getFirst("token");
        }

        if (token == null) {
            return sendError(exchange, HttpStatus.UNAUTHORIZED, "Missing Authorization header");
        }

        // ── 4. Validate JWT signature and expiry ──
        Claims claims;
        try {
            claims = jwtUtil.validateToken(token);
        } catch (Exception e) {
            log.warn("JWT validation failed for path {}: {}", path, e.getMessage());
            return sendError(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }

        // ── 5. Check Redis blocklist (logged-out tokens) ──
        String jti = claims.getId();
        if (jti == null) {
            // Tokens without a jti are still valid but cannot be blocklisted
            return proceedWithIdentity(exchange, chain, claims);
        }

        return reactiveRedisTemplate.hasKey("token:blocklist:" + jti)
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(isBlocked -> {
                    if (Boolean.TRUE.equals(isBlocked)) {
                        log.info("Blocked token used: jti={}", jti);
                        return sendError(exchange, HttpStatus.UNAUTHORIZED, "Token has been revoked");
                    }
                    return proceedWithIdentity(exchange, chain, claims);
                });
    }

    // ───────────────────────────────────────────────────────────────
    // Internal helpers
    // ───────────────────────────────────────────────────────────────

    /**
     * Validate the X-Device-Api-Key header against the configured secret.
     */
    private Mono<Void> handleDeviceAuth(ServerWebExchange exchange, GatewayFilterChain chain) {
        String key = exchange.getRequest().getHeaders().getFirst("X-Device-Api-Key");

        if (deviceApiKey.isBlank()) {
            log.warn("DEVICE_API_KEY is not configured — rejecting device request");
            return sendError(exchange, HttpStatus.SERVICE_UNAVAILABLE, "Device authentication not configured");
        }

        if (key == null || !key.equals(deviceApiKey)) {
            return sendError(exchange, HttpStatus.UNAUTHORIZED, "Invalid device API key");
        }

        // Propagate device identity downstream
        String deviceId = exchange.getRequest().getHeaders().getFirst("X-Device-Id");
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header("X-Auth-Type", "DEVICE")
                .header("X-Device-Id", deviceId != null ? deviceId : "unknown")
                .build();

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /**
     * Inject user identity as downstream headers and continue the filter chain.
     */
    private Mono<Void> proceedWithIdentity(ServerWebExchange exchange,
                                           GatewayFilterChain chain,
                                           Claims claims) {
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header("X-User-Id", claims.getSubject())
                .header("X-User-Role", claimOrDefault(claims, "role", "UNKNOWN"))
                .header("X-User-Email", claimOrDefault(claims, "email", ""))
                .header("X-Auth-Type", "JWT")
                // Remove the original Authorization header so downstream services
                // trust the X-headers rather than re-parsing the token
                .headers(h -> h.remove(HttpHeaders.AUTHORIZATION))
                .build();

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /**
     * Extract the Bearer token from the Authorization header.
     */
    private String extractToken(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return null;
    }

    /**
     * Write a JSON error response and short-circuit the filter chain.
     */
    private Mono<Void> sendError(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {"error": "%s", "status": %d}
                """.formatted(message, status.value());

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(bytes))
        );
    }

    private boolean isOpenPath(String path) {
        return OPEN_PATHS.stream().anyMatch(path::startsWith);
    }

    private boolean isDevicePath(String path) {
        return DEVICE_PATHS.stream().anyMatch(path::startsWith);
    }

    private String claimOrDefault(Claims claims, String key, String defaultValue) {
        Object val = claims.get(key);
        return val != null ? val.toString() : defaultValue;
    }
}
