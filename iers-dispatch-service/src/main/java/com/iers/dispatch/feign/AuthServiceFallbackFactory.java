package com.iers.dispatch.feign;

import com.iers.dispatch.dto.feign.AvailableResponderDto;
import com.iers.dispatch.dto.feign.EmergencyContactDto;
import com.iers.dispatch.dto.feign.InvalidateTokensRequest;
import com.iers.dispatch.dto.feign.MedicalProfileDto;
import com.iers.dispatch.exception.AuthUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Fallback factory for AuthServiceClient.
 *
 * When Auth is down, dispatch MUST still proceed - a person's life may depend on it.
 * But "Auth did not answer" is not the same as "the list is empty":
 *  - emergency contacts: throws, so the contact alert is retried later (it must not be skipped)
 *  - available responders: throws, so the location sync keeps what it has
 */
@Slf4j
@Component
public class AuthServiceFallbackFactory implements FallbackFactory<AuthServiceClient> {

    @Override
    public AuthServiceClient create(Throwable cause) {
        log.warn("Auth Service unavailable - activating fallback. Cause: {}", cause.getMessage());

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
                        .emergencyNotes("Medical profile unavailable - Auth Service down")
                        .build();
            }

            @Override
            public List<EmergencyContactDto> getEmergencyContacts(UUID userId) {
                throw new AuthUnavailableException("Auth cannot give the contacts of user " + userId, cause);
            }

            @Override
            public List<AvailableResponderDto> getAvailableResponders() {
                throw new AuthUnavailableException("Auth cannot give the available responders", cause);
            }

            @Override
            public void updateResponderStatus(UUID userId, String status) {
                log.warn("Fallback: could not update responder {} status to {}", userId, status);
            }

            @Override
            public List<String> getPushTokens(UUID userId) {
                log.warn("Fallback: no push tokens for user {} (Auth down)", userId);
                return Collections.emptyList();
            }

            @Override
            public void invalidatePushTokens(InvalidateTokensRequest request) {
                log.warn("Fallback: could not report {} dead push token(s) to Auth", request.tokens().size());
            }
        };
    }
}
