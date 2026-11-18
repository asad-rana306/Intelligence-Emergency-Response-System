package com.iers.auth.event;

import com.iers.auth.entity.enums.DutyStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Something that happened to a responder's duty status. Sent as JSON on the Kafka topic
 * {@code auth.duty.events.topic} so other services can react (for example Dispatch sends
 * the "You are now OFF duty" push).
 *
 * All fields are plain strings so any language or service can read the message.
 *
 * @param type       DUTY_CHANGED (the responder or Dispatch changed the status) or
 *                   DUTY_EXPIRED (the system put the responder OFF duty)
 * @param userId     the responder's user id
 * @param status     the new status
 * @param reason     for DUTY_EXPIRED: MAX_DURATION (shift ended) or HEARTBEAT_LOST (phone went silent)
 * @param occurredAt ISO-8601 time
 */
public record DutyEvent(String type, String userId, String status, String reason, String occurredAt) {

    public static final String DUTY_CHANGED = "DUTY_CHANGED";
    public static final String DUTY_EXPIRED = "DUTY_EXPIRED";

    public static final String MAX_DURATION = "MAX_DURATION";
    public static final String HEARTBEAT_LOST = "HEARTBEAT_LOST";

    public static DutyEvent changed(UUID userId, DutyStatus status, Instant at) {
        return new DutyEvent(DUTY_CHANGED, userId.toString(), status.name(), null, at.toString());
    }

    public static DutyEvent expired(UUID userId, String reason, Instant at) {
        return new DutyEvent(DUTY_EXPIRED, userId.toString(), DutyStatus.OFF_DUTY.name(), reason, at.toString());
    }
}
