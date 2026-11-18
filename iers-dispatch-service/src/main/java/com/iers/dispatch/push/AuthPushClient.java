package com.iers.dispatch.push;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Looks up a user's FCM tokens from the Auth service's internal endpoint. */
@Slf4j
@Component
public class AuthPushClient {

    private final WebClient http;

    public AuthPushClient(
            @Value("${services.auth.url:http://auth-service:8081}") String baseUrl) {
        this.http = WebClient.builder().baseUrl(baseUrl).build();
    }

    public List<String> tokensFor(UUID userId) {
        try {
            String[] arr = http.get()
                    .uri("/internal/users/{id}/push-tokens", userId)
                    .retrieve()
                    .bodyToMono(String[].class)
                    .block(Duration.ofSeconds(5));
            return arr == null ? Collections.emptyList() : List.of(arr);
        } catch (Exception e) {
            log.error("Could not load push tokens for {}: {}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }
}
