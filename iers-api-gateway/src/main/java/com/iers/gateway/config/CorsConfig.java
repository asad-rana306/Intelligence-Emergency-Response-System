package com.iers.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Global CORS policy for the gateway.
 *
 * In production, lock allowedOrigins down to the specific domains
 * of the phone app's WebView, the dispatcher dashboard, and the
 * hospital pre-alert dashboard. For FYP / local development,
 * wildcard is acceptable.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // TODO: Replace with actual production origins
        config.setAllowedOrigins(List.of("*"));

        config.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "X-Device-Api-Key",
                "X-Device-Id",
                "X-Requested-With"
        ));
        config.setExposedHeaders(List.of(
                "X-RateLimit-Remaining",
                "X-RateLimit-Retry-After-Seconds"
        ));
        config.setMaxAge(3600L); // Pre-flight cache: 1 hour

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsWebFilter(source);
    }
}
