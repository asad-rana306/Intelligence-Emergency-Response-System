package com.iers.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Responder duty rules (prefix {@code auth.duty}).
 *
 * How often the sweep runs is set with {@code auth.duty.sweep.interval} (ISO-8601, e.g. PT30S)
 * directly on the scheduler. Keep {@code sweepLockTtl} shorter than that interval.
 */
@ConfigurationProperties(prefix = "auth.duty")
public record DutyProperties(
        /** A shift lasts this long. After that the responder must go on duty again. */
        @DefaultValue("1h") Duration maxOnDuty,
        /** If no heartbeat or location arrives for this long, the responder is put OFF duty. */
        @DefaultValue("2m") Duration heartbeatTimeout,
        /** The sweep takes a Redis lock for this long, so only one Auth instance runs it per tick. */
        @DefaultValue("25s") Duration sweepLockTtl,
        @DefaultValue Events events
) {
    /** Duty events are published to Kafka so other services (push, live updates) can react. */
    public record Events(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("duty-events") String topic
    ) {
    }
}
