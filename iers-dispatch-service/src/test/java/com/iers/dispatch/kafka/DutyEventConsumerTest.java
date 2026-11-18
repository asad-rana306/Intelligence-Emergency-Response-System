package com.iers.dispatch.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.dispatch.service.NotificationService;
import com.iers.dispatch.service.SpatialQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DutyEventConsumerTest {

    @Mock private SpatialQueryService spatialQueryService;
    @Mock private NotificationService notificationService;

    private DutyEventConsumer consumer;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        consumer = new DutyEventConsumer(new ObjectMapper(), spatialQueryService, notificationService);
    }

    private String json(String type, String status, String reason) {
        return "{\"type\":\"" + type + "\",\"userId\":\"" + userId + "\",\"status\":\"" + status + "\","
                + "\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"")
                + ",\"occurredAt\":\"2026-11-18T10:00:00Z\"}";
    }

    @Test
    @DisplayName("DUTY_EXPIRED (shift over): leaves the pool at once and gets the OFF-duty push")
    void expiredShift() {
        consumer.consume(json("DUTY_EXPIRED", "OFF_DUTY", "MAX_DURATION"));

        verify(spatialQueryService).markResponderOffDuty(userId);
        verify(notificationService).dutyExpired(userId, "MAX_DURATION");
    }

    @Test
    @DisplayName("DUTY_EXPIRED (phone silent)")
    void expiredHeartbeat() {
        consumer.consume(json("DUTY_EXPIRED", "OFF_DUTY", "HEARTBEAT_LOST"));

        verify(notificationService).dutyExpired(userId, "HEARTBEAT_LOST");
    }

    @Test
    @DisplayName("DUTY_CHANGED to OFF_DUTY (the responder did it): leaves the pool, no push")
    void changedOff() {
        consumer.consume(json("DUTY_CHANGED", "OFF_DUTY", null));

        verify(spatialQueryService).markResponderOffDuty(userId);
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("DUTY_CHANGED to ON_DUTY: nothing (the next sync adds the responder with the position)")
    void changedOn() {
        consumer.consume(json("DUTY_CHANGED", "ON_DUTY", null));

        verifyNoInteractions(spatialQueryService, notificationService);
    }

    @Test
    @DisplayName("extra fields from a newer Auth are ignored")
    void unknownFieldsIgnored() {
        consumer.consume("{\"type\":\"DUTY_EXPIRED\",\"userId\":\"" + userId
                + "\",\"status\":\"OFF_DUTY\",\"reason\":\"MAX_DURATION\",\"something\":\"new\"}");

        verify(notificationService).dutyExpired(userId, "MAX_DURATION");
    }

    @Test
    @DisplayName("garbage is logged and dropped, never thrown (a poison message must not block the topic)")
    void badMessages() {
        assertThatCode(() -> {
            consumer.consume("not json");
            consumer.consume("{}");
            consumer.consume("{\"type\":\"DUTY_EXPIRED\",\"userId\":\"not-a-uuid\"}");
            consumer.consume("{\"type\":\"SOMETHING_ELSE\",\"userId\":\"" + userId + "\"}");
        }).doesNotThrowAnyException();

        verifyNoInteractions(spatialQueryService, notificationService);
    }
}
