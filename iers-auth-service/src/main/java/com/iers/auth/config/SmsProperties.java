package com.iers.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Which SMS provider to use and how to reach it (prefix {@code auth.sms}).
 *
 * provider = "log"     : nothing is sent; the message is written to the log (local development).
 * provider = "simgate" : the message is sent through SimGate (your own SIM card).
 *
 * Another provider (Twilio, a local gateway) only needs a new SmsSender class.
 */
@ConfigurationProperties(prefix = "auth.sms")
public record SmsProperties(
        @DefaultValue("log") String provider,
        @DefaultValue SimGate simgate
) {
    public record SimGate(
            @DefaultValue("https://api.simgate.app") String baseUrl,
            String apiKey,
            String deviceId,
            @DefaultValue("3s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout,
            /** Total tries for one SMS (first try + retries on network errors and 5xx). */
            @DefaultValue("2") int maxAttempts
    ) {
    }
}
