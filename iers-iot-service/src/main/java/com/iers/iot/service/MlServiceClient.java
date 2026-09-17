package com.iers.iot.service;

import com.iers.iot.dto.ml.MlPredictionRequest;
import com.iers.iot.dto.ml.MlPredictionResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Synchronous REST client for the external Python ML service.
 *
 * Circuit breaker behavior:
 * - If the ML service is down or slow (>3s), the circuit opens after 5 failures.
 * - The fallback assumes isSevere=true with a default priorityScore of 3.
 * - RATIONALE: In an emergency system, a false positive (dispatching when unnecessary)
 *   is far less dangerous than a false negative (not dispatching when someone is dying).
 */
@Slf4j
@Service
public class MlServiceClient {

    private final RestClient restClient;

    @Autowired
    public MlServiceClient(@Value("${iot.ml-service.url:http://localhost:8000}") String mlBaseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(mlBaseUrl)
                .build();
    }

    // Package-private constructor for testing
    MlServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @CircuitBreaker(name = "mlService", fallbackMethod = "fallbackPredict")
    public MlPredictionResponse predict(MlPredictionRequest request) {
        log.info("Calling ML service: gForce={}, rollover={}, speed={}",
                request.getGForce(), request.getRolloverAngle(), request.getSpeed());

        MlPredictionResponse response = restClient.post()
                .uri("/predict")
                .body(request)
                .retrieve()
                .body(MlPredictionResponse.class);

        log.info("ML response: severe={}, priorityScore={}",
                response != null && response.isSevere(),
                response != null ? response.getPriorityScore() : "null");

        return response;
    }

    /**
     * Fail-safe fallback: assume the worst case.
     * This method signature must match the original method + an extra Exception parameter.
     */
    @SuppressWarnings("unused")
    private MlPredictionResponse fallbackPredict(MlPredictionRequest request, Exception ex) {
        log.warn("ML service unavailable — applying fail-safe fallback. Cause: {}", ex.getMessage());

        return MlPredictionResponse.builder()
                .severe(true)
                .priorityScore(3)
                .severity("UNKNOWN_FALLBACK")
                .build();
    }
}
