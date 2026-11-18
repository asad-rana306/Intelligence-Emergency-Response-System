package com.iers.auth.service;

import com.iers.auth.config.DutyProperties;
import com.iers.auth.entity.ResponderProfile;
import com.iers.auth.entity.enums.DutyStatus;
import com.iers.auth.event.DutyEvent;
import com.iers.auth.event.DutyEventPublisher;
import com.iers.auth.repository.ResponderProfileRepository;
import com.iers.auth.util.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Puts responders OFF duty when their shift is over or their phone went silent.
 *
 * Safe with many Auth instances: every row is changed with a conditional UPDATE
 * ({@code expireIfStillDue}), so only the instance that really changed the row
 * publishes the event. Running the sweep twice at the same time does no harm.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DutyExpiryService {

    static final int BATCH_SIZE = 200;
    /** Upper limit per sweep (10 x 200 = 2000 responders), so one sweep can never run forever. */
    static final int MAX_BATCHES = 10;

    private final ResponderProfileRepository repository;
    private final DutyProperties props;
    private final DutyEventPublisher eventPublisher;
    private final Clock clock;

    /** @return how many responders this call put OFF duty */
    @Transactional
    public int expireDue() {
        Instant now = clock.instant();
        Instant staleBefore = now.minus(props.heartbeatTimeout());
        int expired = 0;

        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<ResponderProfile> due = repository.findDue(
                    DutyStatus.ON_DUTY, now, staleBefore, PageRequest.of(0, BATCH_SIZE));
            if (due.isEmpty()) {
                break;
            }
            for (ResponderProfile profile : due) {
                // Read everything we need BEFORE the update (the update clears the session).
                var userId = profile.getUser().getId();
                String reason = reasonFor(profile, now);

                int changed = repository.expireIfStillDue(
                        profile.getId(), now, staleBefore, DutyStatus.ON_DUTY, DutyStatus.OFF_DUTY);
                if (changed == 1) {
                    expired++;
                    DutyEvent event = DutyEvent.expired(userId, reason, now);
                    AfterCommit.run(() -> eventPublisher.publish(event));
                    log.info("Responder {} put OFF duty ({})", userId, reason);
                }
            }
            if (due.size() < BATCH_SIZE) {
                break;
            }
        }
        return expired;
    }

    private static String reasonFor(ResponderProfile profile, Instant now) {
        Instant expiresAt = profile.getDutyExpiresAt();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            return DutyEvent.MAX_DURATION;
        }
        return DutyEvent.HEARTBEAT_LOST;
    }
}
