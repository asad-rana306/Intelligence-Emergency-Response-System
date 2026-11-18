package com.iers.dispatch.feign;

import com.iers.dispatch.dto.feign.InvalidateTokensRequest;
import com.iers.dispatch.dto.feign.MedicalProfileDto;
import com.iers.dispatch.exception.AuthUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceFallbackFactoryTest {

    private final RuntimeException cause = new RuntimeException("connection refused");
    private final AuthServiceClient fallback = new AuthServiceFallbackFactory().create(cause);
    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("contacts: Auth down is an error, NOT an empty list (the alert must be retried, not skipped)")
    void contactsThrow() {
        assertThatThrownBy(() -> fallback.getEmergencyContacts(userId))
                .isInstanceOf(AuthUnavailableException.class)
                .hasCause(cause);
    }

    @Test
    @DisplayName("available responders: Auth down is an error (the sync must not empty the pool)")
    void respondersThrow() {
        assertThatThrownBy(fallback::getAvailableResponders)
                .isInstanceOf(AuthUnavailableException.class)
                .hasCause(cause);
    }

    @Test
    @DisplayName("medical profile: UNKNOWN values, so the ambulance is still sent")
    void profileUnknown() {
        MedicalProfileDto profile = fallback.getMedicalProfile(userId);
        assertThat(profile.getBloodType()).isEqualTo("UNKNOWN");
        assertThat(profile.getUserId()).isEqualTo(userId.toString());
    }

    @Test
    @DisplayName("push tokens: empty list; the other calls do nothing and never throw")
    void softFallbacks() {
        assertThat(fallback.getPushTokens(userId)).isEmpty();
        assertThatCode(() -> fallback.updateResponderStatus(userId, "ON_DUTY")).doesNotThrowAnyException();
        assertThatCode(() -> fallback.invalidatePushTokens(new InvalidateTokensRequest(List.of("t"))))
                .doesNotThrowAnyException();
    }
}
