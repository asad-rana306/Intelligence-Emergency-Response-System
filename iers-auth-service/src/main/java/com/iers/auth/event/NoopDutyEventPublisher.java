package com.iers.auth.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Used when {@code auth.duty.events.enabled} is false (the default). Does nothing. */
@Component
@ConditionalOnProperty(prefix = "auth.duty.events", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopDutyEventPublisher implements DutyEventPublisher {

    @Override
    public void publish(DutyEvent event) {
        // events are switched off
    }
}
