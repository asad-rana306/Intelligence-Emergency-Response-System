package com.iers.gateway.controller;

import com.iers.gateway.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * Circuit-breaker fallback endpoint.
 *
 * When a downstream service (Auth, IoT, Dispatch) is unreachable and the
 * gateway-level circuit breaker opens, the request is forwarded here
 * instead of timing out. This gives the client a fast, informative failure.
 *
 * Configured in application.yml under each route's CircuitBreaker filter:
 *   filters:
 *     - name: CircuitBreaker
 *       args:
 *         fallbackUri: forward:/fallback/auth
 */
@RestController
public class FallbackController {

    @GetMapping("/fallback/auth")
    public ResponseEntity<ErrorResponse> authFallback(ServerWebExchange exchange) {
        return buildFallback("Auth & Identity Service is temporarily unavailable",
                exchange.getRequest().getURI().getPath());
    }

    @GetMapping("/fallback/iot")
    public ResponseEntity<ErrorResponse> iotFallback(ServerWebExchange exchange) {
        return buildFallback("IoT & Telemetry Service is temporarily unavailable",
                exchange.getRequest().getURI().getPath());
    }

    @GetMapping("/fallback/dispatch")
    public ResponseEntity<ErrorResponse> dispatchFallback(ServerWebExchange exchange) {
        return buildFallback("Dispatch & Notification Service is temporarily unavailable",
                exchange.getRequest().getURI().getPath());
    }

    /**
     * Generic catch-all fallback for any route not covered above.
     */
    @GetMapping("/fallback")
    public ResponseEntity<ErrorResponse> genericFallback(ServerWebExchange exchange) {
        return buildFallback("Downstream service is temporarily unavailable",
                exchange.getRequest().getURI().getPath());
    }

    private ResponseEntity<ErrorResponse> buildFallback(String message, String path) {
        ErrorResponse error = ErrorResponse.builder()
                .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                .error("Service Unavailable")
                .message(message)
                .path(path)
                .build();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
    }
}
