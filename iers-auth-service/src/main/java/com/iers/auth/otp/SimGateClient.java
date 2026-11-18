package com.iers.auth.otp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Sends one SMS through SimGate (https://api.simgate.app).
 * Returns true if the gateway accepted or queued the message.
 * Never throws; a failure is just logged.
 */
@Slf4j
@Component
public class SimGateClient {

    private final WebClient http;
    private final String apiKey;
    private final String deviceId;

    public SimGateClient(
            @Value("${simgate.base-url:https://api.simgate.app}") String baseUrl,
            @Value("${simgate.api-key:}") String apiKey,
            @Value("${simgate.device-id:}") String deviceId) {
        this.apiKey = apiKey;
        this.deviceId = deviceId;
        this.http = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("x-api-key", apiKey)
                .build();
    }

    public boolean send(String phoneE164, String message) {
        if (apiKey.isBlank() || deviceId.isBlank()) {
            log.warn("SimGate not configured; skipping SMS");
            return false;
        }
        try {
            http.post()
                .uri("/v1/sms/send")
                .bodyValue(Map.of(
                        "deviceId", deviceId,
                        "phoneNumber", phoneE164,
                        "message", message))
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(10));
            log.info("SMS sent to {}", mask(phoneE164));
            return true;
        } catch (Exception e) {
            log.error("SMS to {} failed: {}", mask(phoneE164), e.getMessage());
            return false;
        }
    }

    private static String mask(String p) {
        if (p == null || p.length() < 4) return "***";
        return "***" + p.substring(p.length() - 4);
    }
}
