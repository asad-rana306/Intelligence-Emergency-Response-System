package com.iers.dispatch.feign;

import com.iers.dispatch.dto.feign.AvailableResponderDto;
import com.iers.dispatch.dto.feign.EmergencyContactDto;
import com.iers.dispatch.dto.feign.MedicalProfileDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Fallback factory for AuthServiceClient.
 *
 * When Auth Service is down, dispatch MUST still proceed — a person's life
 * may depend on it. The fallback returns "UNKNOWN" for medical fields and
 * an empty contact list (SMS to family skipped, but ambulance still dispatched).
 */
@Slf4j
@Component
public class AuthServiceFallbackFactory implements FallbackFactory<AuthServiceClient> {

    @Override
    public AuthServiceClient create(Throwable cause) {
        log.warn("Auth Service unavailable — activating fallback. Cause: {}", cause.getMessage());

        return new AuthServiceClient() {

            @Override
            public MedicalProfileDto getMedicalProfile(UUID userId) {
                log.warn("Fallback: returning UNKNOWN medical profile for user {}", userId);
                return MedicalProfileDto.builder()
                        .userId(userId.toString())
                        .bloodType("UNKNOWN")
                        .allergies("UNKNOWN")
                        .medications("UNKNOWN")
                        .chronicConditions("UNKNOWN")
                        .emergencyNotes("Medical profile unavailable — Auth Service down")
                        .build();
            }

            @Override
            public List<EmergencyContactDto> getEmergencyContacts(UUID userId) {
                log.warn("Fallback: returning empty emergency contacts for user {}", userId);
                return Collections.emptyList();
            }

            @Override
            public List<AvailableResponderDto> getAvailableResponders() {
                log.warn("Fallback: returning empty available responders list");
                return Collections.emptyList();
            }

            @Override
            public void updateResponderStatus(UUID userId, String status) {
                log.warn("Fallback: could not update responder {} status to {}", userId, status);
            }
        };
    }
}
