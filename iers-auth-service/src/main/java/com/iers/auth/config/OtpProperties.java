package com.iers.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for the SMS one-time code (OTP). All of them can be changed in application.yml
 * (prefix {@code auth.otp}) or with environment variables, with no code change.
 */
@ConfigurationProperties(prefix = "auth.otp")
public record OtpProperties(
        /** If true, /auth/register is refused unless the phone number was verified with a code. */
        @DefaultValue("true") boolean requiredOnRegister,
        /** Number of digits in the code (clamped to 4..9). */
        @DefaultValue("6") int codeLength,
        /** How long a code stays valid. */
        @DefaultValue("5m") Duration codeTtl,
        /** Wrong guesses allowed per code before the code is burned. */
        @DefaultValue("5") int maxVerifyAttempts,
        /** Codes that may be sent to one phone number inside {@link #sendWindow}. */
        @DefaultValue("3") int maxSendsPerWindow,
        @DefaultValue("1h") Duration sendWindow,
        /** Minimum time between two codes for the same phone number. */
        @DefaultValue("30s") Duration resendCooldown,
        /** After a correct code, how long the phone counts as verified (time to finish registering). */
        @DefaultValue("10m") Duration verifiedTtl
) {
}
