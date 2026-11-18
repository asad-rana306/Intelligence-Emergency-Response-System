package com.iers.auth.service;

import com.iers.auth.config.DutyProperties;
import com.iers.auth.dto.request.UpdateDutyStatusRequest;
import com.iers.auth.dto.request.UpdateLocationRequest;
import com.iers.auth.dto.response.AvailableResponderResponse;
import com.iers.auth.dto.response.ResponderProfileResponse;
import com.iers.auth.entity.ResponderProfile;
import com.iers.auth.entity.enums.DutyStatus;
import com.iers.auth.event.DutyEvent;
import com.iers.auth.event.DutyEventPublisher;
import com.iers.auth.exception.ResourceNotFoundException;
import com.iers.auth.repository.ResponderProfileRepository;
import com.iers.auth.util.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Responder duty rules.
 *
 *   OFF_DUTY    no shift. Never offered incidents.
 *   ON_DUTY     a shift of {@code auth.duty.max-on-duty} (default 1 hour). Every time the
 *               responder goes on duty a NEW shift starts. When the shift ends, or the phone
 *               stops sending heartbeats, {@link DutyExpiryService} puts the responder OFF duty.
 *   ON_MISSION  set by Dispatch while the responder handles an incident. The shift timers are
 *               kept but not enforced.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResponderService {

    /** Statuses in which the phone is expected to keep reporting. */
    private static final Set<DutyStatus> ACTIVE = EnumSet.of(DutyStatus.ON_DUTY, DutyStatus.ON_MISSION);

    private final ResponderProfileRepository responderProfileRepository;
    private final DutyProperties dutyProperties;
    private final DutyEventPublisher eventPublisher;
    private final Clock clock;

    /** The responder changes their own status (the big ON / OFF button, or "Go on duty again"). */
    @Transactional
    public ResponderProfileResponse updateDutyStatus(UUID userId, UpdateDutyStatusRequest request) {
        ResponderProfile profile = load(userId);
        Instant now = clock.instant();

        applyStatus(userId, profile, request.getStatus(), now);
        profile = responderProfileRepository.save(profile);

        log.info("Responder {} duty status updated to {}", userId, request.getStatus());
        return ResponderProfileResponse.from(profile, now);
    }

    /** GPS position. Also counts as a sign of life while the responder is active. */
    @Transactional
    public ResponderProfileResponse updateLocation(UUID userId, UpdateLocationRequest request) {
        ResponderProfile profile = load(userId);
        Instant now = clock.instant();

        profile.setCurrentLat(request.getLatitude());
        profile.setCurrentLng(request.getLongitude());
        if (ACTIVE.contains(profile.getDutyStatus())) {
            profile.setLastHeartbeatAt(now);
        }
        profile = responderProfileRepository.save(profile);

        return ResponderProfileResponse.from(profile, now);
    }

    /**
     * "I am still here" from the background service (about every 30 seconds).
     * The answer carries the current status and the seconds left, so the app learns
     * at once if the server put it OFF duty.
     */
    @Transactional
    public ResponderProfileResponse heartbeat(UUID userId) {
        ResponderProfile profile = load(userId);
        Instant now = clock.instant();

        if (ACTIVE.contains(profile.getDutyStatus())) {
            profile.setLastHeartbeatAt(now);
            profile = responderProfileRepository.save(profile);
        }
        return ResponderProfileResponse.from(profile, now);
    }

    @Transactional(readOnly = true)
    public ResponderProfileResponse getProfile(UUID userId) {
        return ResponderProfileResponse.from(load(userId), clock.instant());
    }

    /**
     * Responders that Dispatch may offer an incident to. Called internally via Feign.
     * Only ON_DUTY responders with a position, an unfinished shift and a recent heartbeat.
     */
    @Transactional(readOnly = true)
    public List<AvailableResponderResponse> getAvailableResponders() {
        Instant now = clock.instant();
        Instant freshAfter = now.minus(dutyProperties.heartbeatTimeout());
        return responderProfileRepository
                .findAvailable(DutyStatus.ON_DUTY, now, freshAfter)
                .stream()
                .map(AvailableResponderResponse::from)
                .toList();
    }

    /**
     * Status change made by Dispatch (ON_MISSION when a responder accepts, back to ON_DUTY when
     * the incident ends). Dispatch can only bring a responder back to ON_DUTY from ON_MISSION:
     * a responder who switched OFF (or was switched OFF) during the mission stays OFF.
     */
    @Transactional
    public void updateStatusByUserId(UUID userId, DutyStatus status) {
        ResponderProfile profile = load(userId);

        if (status == DutyStatus.ON_DUTY && profile.getDutyStatus() != DutyStatus.ON_MISSION) {
            log.info("Internal status update ignored: responder {} is {}, not ON_MISSION",
                    userId, profile.getDutyStatus());
            return;
        }

        applyStatus(userId, profile, status, clock.instant());
        responderProfileRepository.save(profile);
        log.info("Internal status update: responder {} → {}", userId, status);
    }

    // ──────────────────── Helpers ────────────────────

    private ResponderProfile load(UUID userId) {
        return responderProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Responder profile not found for user: " + userId));
    }

    private void applyStatus(UUID userId, ResponderProfile profile, DutyStatus status, Instant now) {
        DutyStatus before = profile.getDutyStatus();

        switch (status) {
            case ON_DUTY -> {
                // Every ON_DUTY starts a new shift (this is what "go on duty again" does).
                profile.setDutyStartedAt(now);
                profile.setDutyExpiresAt(now.plus(dutyProperties.maxOnDuty()));
                profile.setLastHeartbeatAt(now);
            }
            case OFF_DUTY -> {
                profile.setDutyStartedAt(null);
                profile.setDutyExpiresAt(null);
                profile.setLastHeartbeatAt(null);
            }
            case ON_MISSION -> profile.setLastHeartbeatAt(now);
        }
        profile.setDutyStatus(status);

        if (before != status) {
            DutyEvent event = DutyEvent.changed(userId, status, now);
            AfterCommit.run(() -> eventPublisher.publish(event));
        }
    }
}
