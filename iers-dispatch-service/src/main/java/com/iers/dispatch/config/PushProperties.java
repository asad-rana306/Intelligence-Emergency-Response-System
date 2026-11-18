package com.iers.dispatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Firebase Cloud Messaging settings (prefix {@code dispatch.fcm}).
 *
 * If {@code enabled} is true but no credentials path / project id is given, push is only
 * logged (local development). If a path IS given but the file cannot be read, the service
 * refuses to start, so a broken key is noticed on day one and not during an emergency.
 */
@ConfigurationProperties(prefix = "dispatch.fcm")
public record PushProperties(
        @DefaultValue("true") boolean enabled,
        String credentialsPath,
        String projectId,
        /** How long FCM may keep an ambulance offer for a phone that is offline. */
        @DefaultValue("30s") Duration offerTtl
) {
    public boolean hasCredentials() {
        return credentialsPath != null && !credentialsPath.isBlank()
                && projectId != null && !projectId.isBlank();
    }
}
