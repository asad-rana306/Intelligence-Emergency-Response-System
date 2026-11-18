package com.iers.dispatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Contact alerts and background workers (prefix {@code dispatch.notify}). */
@ConfigurationProperties(prefix = "dispatch.notify")
public record NotifyProperties(
        /** Page where a contact can follow the incident. The incident id is added to the end. */
        @DefaultValue("http://localhost:3000/track") String trackingBaseUrl,
        /** National emergency number printed in the SMS. */
        @DefaultValue("1122") String emergencyNumber,
        /** Wait before trying again when an SMS could not be sent. */
        @DefaultValue("20s") Duration contactRetryInterval,
        /** How long one worker owns an alert. Another instance may take it over after this. */
        @DefaultValue("2m") Duration contactLease,
        /** No more retries for an incident older than this. */
        @DefaultValue("30m") Duration giveUpAfter,
        /** How many alerts one sweep picks up. */
        @DefaultValue("20") int batchSize,
        @DefaultValue Executor executor
) {
    public record Executor(
            @DefaultValue("4") int threads,
            @DefaultValue("500") int queueCapacity
    ) {
    }
}
