package com.iers.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Push notification token storage (prefix {@code auth.push}). */
@ConfigurationProperties(prefix = "auth.push")
public record PushProperties(
        /** A user can be logged in on several phones. Only the newest N tokens are kept. */
        @DefaultValue("5") int maxTokensPerUser
) {
}
