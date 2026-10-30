package com.iers.realtime.websocket;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayIdentityFilterTest {

    private static final String USER = "a1b2c3d4-0000-4000-8000-000000000001";

    private final GatewayIdentityFilter filter = new GatewayIdentityFilter();

    private MockHttpServletRequest request(String uri, String userHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        if (userHeader != null) {
            request.addHeader("X-User-Id", userHeader);
        }
        return request;
    }

    @Test
    @DisplayName("A WebSocket request with a good X-User-Id gets that user as its Principal")
    void goodHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/ws/live/websocket", USER), response, chain);

        HttpServletRequest passed = (HttpServletRequest) chain.getRequest();
        assertThat(passed).isNotNull();
        assertThat(passed.getUserPrincipal().getName()).isEqualTo(USER);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("The user ID is written in lower case, the way the Kafka events write it")
    void upperCaseHeader() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/ws/live/info", USER.toUpperCase()), new MockHttpServletResponse(), chain);

        assertThat(((HttpServletRequest) chain.getRequest()).getUserPrincipal().getName()).isEqualTo(USER);
    }

    @Test
    @DisplayName("No header: 401 in the service error shape, and the request goes no further")
    void missingHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/ws/live/websocket", null), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"status\":401").contains("\"error\":\"Unauthorized\"")
                .contains("\"message\"").contains("\"path\":\"/ws/live/websocket\"").contains("\"timestamp\"");
    }

    @Test
    @DisplayName("A header that is not a UUID is refused")
    void badHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/ws/live/websocket", "admin"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Paths outside /ws/ (the health check) are not touched")
    void otherPaths() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest health = request("/actuator/health", null);

        filter.doFilter(health, response, chain);

        assertThat(chain.getRequest()).isSameAs(health);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("userIdOf reads UUIDs only")
    void userIdOf() {
        assertThat(GatewayIdentityFilter.userIdOf(" " + USER + " ")).isEqualTo(USER);
        assertThat(GatewayIdentityFilter.userIdOf(null)).isNull();
        assertThat(GatewayIdentityFilter.userIdOf("")).isNull();
        assertThat(GatewayIdentityFilter.userIdOf("not-a-uuid")).isNull();
    }
}
