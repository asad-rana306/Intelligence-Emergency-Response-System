package com.iers.realtime.websocket;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

/**
 * Who is on the other end of a WebSocket?
 *
 * The gateway has already checked the login (?token=...) and passes the user ID on as the
 * X-User-Id header. This filter turns that header into the connection's user, so every
 * message to "/user/queue/..." reaches exactly this person.
 * No header, or not a UUID: the connection is refused with 401.
 *
 * Only paths under /ws/ are touched (the health check stays open).
 * Because the service trusts the header, it must only be reachable through the gateway.
 */
@Component
public class GatewayIdentityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayIdentityFilter.class);
    static final String USER_HEADER = "X-User-Id";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/ws/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userId = userIdOf(request.getHeader(USER_HEADER));
        if (userId == null) {
            log.warn("WebSocket request without a valid {} header: {}", USER_HEADER, request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\","
                    + "\"message\":\"Connect through the gateway with ?token=<jwt>\","
                    + "\"path\":\"" + request.getRequestURI().replace("\"", "") + "\","
                    + "\"timestamp\":\"" + Instant.now() + "\"}");
            return;
        }
        chain.doFilter(new IdentifiedRequest(request, new StompUser(userId)), response);
    }

    /** The ID in lower-case UUID form, or null when the header is missing or not a UUID. */
    static String userIdOf(String header) {
        if (header == null) {
            return null;
        }
        try {
            return UUID.fromString(header.trim()).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final class IdentifiedRequest extends HttpServletRequestWrapper {
        private final Principal user;

        IdentifiedRequest(HttpServletRequest request, Principal user) {
            super(request);
            this.user = user;
        }

        @Override
        public Principal getUserPrincipal() {
            return user;
        }
    }
}
