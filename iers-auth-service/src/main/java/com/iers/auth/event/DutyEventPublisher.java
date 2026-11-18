package com.iers.auth.event;

/**
 * Where duty events go. Publishing must never break the caller: an implementation logs
 * its own failures and returns normally.
 */
public interface DutyEventPublisher {

    void publish(DutyEvent event);
}
