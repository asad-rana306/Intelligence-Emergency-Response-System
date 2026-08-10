package com.iers.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * Defines the key-resolution strategies for Spring Cloud Gateway's
 * built-in Redis-backed rate limiter (Token Bucket algorithm).
 *
 * The active resolver is selected in application.yml via:
 *   key-resolver: "#{@ipKeyResolver}"       — throttle per client IP
 *   key-resolver: "#{@userKeyResolver}"      — throttle per authenticated user
 *   key-resolver: "#{@combinedKeyResolver}"  — throttle per user+path
 */
@Configuration
public class RateLimiterConfig {

    /**
     * Default: rate-limit by client IP address.
     * Suitable for unauthenticated endpoints (login, register, device ingestion).
     */
    @Bean
    @Primary
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            String ip = exchange.getRequest().getRemoteAddress() != null
                    ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                    : "unknown";
            return Mono.just(ip);
        };
    }

    /**
     * Rate-limit by authenticated user ID (set by JwtAuthenticationFilter).
     * Falls back to IP if the user header is absent (pre-auth endpoints).
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return Mono.just("user:" + userId);
            }
            // Fallback to IP for unauthenticated requests
            String ip = exchange.getRequest().getRemoteAddress() != null
                    ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                    : "unknown";
            return Mono.just("ip:" + ip);
        };
    }

    /**
     * Fine-grained: rate-limit by user + path prefix.
     * Prevents a single user from hammering one specific service.
     */
    @Bean
    public KeyResolver combinedKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            String path = exchange.getRequest().getURI().getPath();
            // Extract the first two path segments (e.g., /api/telemetry)
            String[] segments = path.split("/");
            String prefix = segments.length >= 3
                    ? "/" + segments[1] + "/" + segments[2]
                    : path;

            String key = (userId != null ? userId : "anon") + ":" + prefix;
            return Mono.just(key);
        };
    }
}
