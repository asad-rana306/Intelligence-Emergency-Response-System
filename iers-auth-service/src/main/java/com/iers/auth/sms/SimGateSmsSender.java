package com.iers.auth.sms;

import com.iers.auth.config.SmsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Sends SMS through SimGate (https://api.simgate.app), which uses a real SIM card.
 *
 *   POST {baseUrl}/v1/sms/send   header x-api-key
 *   body {"deviceId": "...", "phoneNumber": "+92...", "message": "..."}
 *   200 = accepted for immediate delivery, 202 = queued while the phone reconnects.
 *
 * Network errors and 5xx answers are retried (auth.sms.simgate.max-attempts). A 4xx answer
 * (bad key, bad number) is not retried. The message text is never logged (it holds OTP codes).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "auth.sms", name = "provider", havingValue = "simgate")
public class SimGateSmsSender implements SmsSender {

    private final RestClient http;
    private final String deviceId;
    private final int maxAttempts;

    public SimGateSmsSender(SmsProperties props) {
        SmsProperties.SimGate cfg = props.simgate();
        if (isBlank(cfg.apiKey()) || isBlank(cfg.deviceId())) {
            throw new IllegalStateException(
                    "auth.sms.provider=simgate needs auth.sms.simgate.api-key and auth.sms.simgate.device-id "
                            + "(environment variables SIMGATE_API_KEY and SIMGATE_DEVICE_ID)");
        }
        this.deviceId = cfg.deviceId();
        this.maxAttempts = Math.max(1, cfg.maxAttempts());

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) cfg.connectTimeout().toMillis());
        factory.setReadTimeout((int) cfg.readTimeout().toMillis());

        this.http = RestClient.builder()
                .baseUrl(cfg.baseUrl())
                .requestFactory(factory)
                .defaultHeader("x-api-key", cfg.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("SMS provider: SimGate ({}), device {}", cfg.baseUrl(), cfg.deviceId());
    }

    @Override
    public SmsResult send(String toE164, String message) {
        String lastError = "unknown error";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ResponseEntity<Void> res = http.post()
                        .uri("/v1/sms/send")
                        .body(Map.of("deviceId", deviceId, "phoneNumber", toE164, "message", message))
                        .retrieve()
                        .toBodilessEntity();
                int code = res.getStatusCode().value();
                log.info("SimGate accepted SMS to {} (HTTP {})", mask(toE164), code);
                return code == 202 ? SmsResult.queued() : SmsResult.sent();
            } catch (RestClientResponseException e) {
                lastError = "HTTP " + e.getStatusCode().value();
                if (e.getStatusCode().is4xxClientError()) {
                    log.error("SimGate refused SMS to {}: {}", mask(toE164), lastError);
                    return SmsResult.failed(lastError);
                }
                log.warn("SimGate error for {} (attempt {}/{}): {}", mask(toE164), attempt, maxAttempts, lastError);
            } catch (ResourceAccessException e) {
                lastError = "network error: " + e.getMessage();
                log.warn("SimGate unreachable for {} (attempt {}/{}): {}", mask(toE164), attempt, maxAttempts, lastError);
            } catch (RuntimeException e) {
                lastError = e.getClass().getSimpleName();
                log.error("Unexpected SMS error for {}: {}", mask(toE164), lastError);
                return SmsResult.failed(lastError);
            }
            pause(attempt);
        }
        return SmsResult.failed(lastError);
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(200L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() < 4) return "***";
        return "***" + phone.substring(phone.length() - 4);
    }
}
