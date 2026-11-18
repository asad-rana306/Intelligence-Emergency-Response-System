package com.iers.dispatch.feign;

import com.iers.dispatch.dto.feign.AvailableResponderDto;
import com.iers.dispatch.dto.feign.EmergencyContactDto;
import com.iers.dispatch.dto.feign.InvalidateTokensRequest;
import com.iers.dispatch.dto.feign.MedicalProfileDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

/**
 * Feign client for Auth & Identity Service.
 * Calls go directly via Eureka (not through the API Gateway).
 *
 * Timeouts and the circuit breaker are set in application.yml. When Auth cannot answer,
 * AuthServiceFallbackFactory decides what each call returns:
 *  - medical profile            : "UNKNOWN" values (the ambulance must still be sent)
 *  - push tokens                : empty list
 *  - invalidate tokens, status  : nothing (logged)
 *  - emergency contacts, available responders : AuthUnavailableException, so the caller can
 *    tell "Auth is down" (try again later) from "there are none" (nothing to do)
 */
@FeignClient(
        name = "auth-service",
        fallbackFactory = AuthServiceFallbackFactory.class
)
public interface AuthServiceClient {

    @GetMapping("/internal/users/{userId}/medical-profile")
    MedicalProfileDto getMedicalProfile(@PathVariable("userId") UUID userId);

    /** Phones are already in international form (+92...). */
    @GetMapping("/internal/users/{userId}/emergency-contacts")
    List<EmergencyContactDto> getEmergencyContacts(@PathVariable("userId") UUID userId);

    /** Only responders that are ON_DUTY, have a position, an unfinished shift and a fresh heartbeat. */
    @GetMapping("/internal/responders/available")
    List<AvailableResponderDto> getAvailableResponders();

    @PutMapping("/internal/responders/{userId}/status")
    void updateResponderStatus(@PathVariable("userId") UUID userId, @RequestParam("status") String status);

    /** Firebase tokens of all phones of this user, newest first. */
    @GetMapping("/internal/users/{userId}/push-tokens")
    List<String> getPushTokens(@PathVariable("userId") UUID userId);

    /** Tells Auth that FCM says these tokens are dead (1 to 500 per call). */
    @PostMapping("/internal/push-tokens/invalidate")
    void invalidatePushTokens(@RequestBody InvalidateTokensRequest request);
}
