package com.iers.dispatch.feign;

import com.iers.dispatch.dto.feign.AvailableResponderDto;
import com.iers.dispatch.dto.feign.EmergencyContactDto;
import com.iers.dispatch.dto.feign.MedicalProfileDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

/**
 * Feign client for Auth & Identity Service (Service 2).
 * Calls go directly via Eureka (not through the API Gateway) for efficiency.
 *
 * Circuit breaker: if Auth is down, AuthServiceFallbackFactory provides
 * minimal fallback data so dispatch can still proceed.
 */
@FeignClient(
        name = "auth-service",
        fallbackFactory = AuthServiceFallbackFactory.class
)
public interface AuthServiceClient {

    @GetMapping("/internal/users/{userId}/medical-profile")
    MedicalProfileDto getMedicalProfile(@PathVariable UUID userId);

    @GetMapping("/internal/users/{userId}/emergency-contacts")
    List<EmergencyContactDto> getEmergencyContacts(@PathVariable UUID userId);

    @GetMapping("/internal/responders/available")
    List<AvailableResponderDto> getAvailableResponders();

    @PutMapping("/internal/responders/{userId}/status")
    void updateResponderStatus(@PathVariable UUID userId, @RequestParam String status);
}
