package com.iers.dispatch.service;

import com.iers.dispatch.dto.feign.AvailableResponderDto;
import com.iers.dispatch.entity.ResponderLocation;
import com.iers.dispatch.feign.AuthServiceClient;
import com.iers.dispatch.repository.ResponderLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpatialQueryService {

    static final String ON_DUTY = "ON_DUTY";
    static final String OFF_DUTY = "OFF_DUTY";
    static final String ON_MISSION = "ON_MISSION";

    private final ResponderLocationRepository locationRepository;
    private final AuthServiceClient authServiceClient;

    /**
     * Find the nearest available responder to the crash site,
     * excluding any responders already attempted for this incident.
     */
    public ResponderLocation findNearestResponder(double lat, double lng,
                                                  List<UUID> excludedResponderIds) {
        List<ResponderLocation> results;

        if (excludedResponderIds == null || excludedResponderIds.isEmpty()) {
            results = locationRepository.findNearestRespondersNoExclusion(lat, lng, 1);
        } else {
            results = locationRepository.findNearestResponders(lat, lng, excludedResponderIds, 1);
        }

        if (results.isEmpty()) {
            log.warn("No available responders found near [{}, {}]", lat, lng);
            return null;
        }

        ResponderLocation nearest = results.get(0);
        log.info("Nearest responder: {} (vehicle={}) at [{}, {}]",
                nearest.getUserName(), nearest.getVehicleId(),
                nearest.getLatitude(), nearest.getLongitude());
        return nearest;
    }

    /**
     * Sync responder locations from Auth Service every 15 seconds.
     * This keeps the local PostGIS-indexed table current for spatial queries.
     *
     * The key is the responder's USER id (dto.userId), the same id the phone sends in X-User-Id.
     *
     * Auth's list holds only responders that are really available (on duty, shift not over,
     * phone reported recently). So when Auth ANSWERS, everybody who is ON_DUTY here but not in
     * the list is set OFF_DUTY. That includes an EMPTY list: the last responder going off duty
     * (shift over) must disappear too.
     *
     * If Auth does NOT answer (down, slow, circuit open) the fallback throws
     * AuthUnavailableException and nothing is changed. A real outage must not empty the pool.
     *
     * Not @Transactional on purpose: the call to Auth can take seconds, and a database
     * connection must not wait for it. The writes below are one short transaction (saveAll).
     */
    @Scheduled(fixedRate = 15000)
    public void syncResponderLocations() {
        List<AvailableResponderDto> responders;
        try {
            responders = authServiceClient.getAvailableResponders();
        } catch (Exception e) {
            log.warn("Responder sync skipped, Auth did not answer: {}", e.getMessage());
            return;
        }
        if (responders == null) {
            return;
        }

        try {
            Set<UUID> seen = new HashSet<>();
            List<ResponderLocation> changed = new ArrayList<>();

            for (AvailableResponderDto dto : responders) {
                if (dto.getUserId() == null) {
                    continue;
                }
                UUID userId = UUID.fromString(dto.getUserId());
                seen.add(userId);

                ResponderLocation loc = locationRepository.findByResponderId(userId)
                        .orElse(ResponderLocation.builder().responderId(userId).build());

                loc.setUserName(dto.getFullName());
                loc.setPhone(dto.getPhone());
                loc.setVehicleId(dto.getVehicleId());
                loc.setDutyStatus(ON_DUTY);
                loc.setLatitude(dto.getLatitude());
                loc.setLongitude(dto.getLongitude());
                loc.setLastUpdated(Instant.now());
                changed.add(loc);
            }

            for (ResponderLocation loc : locationRepository.findByDutyStatus(ON_DUTY)) {
                if (!seen.contains(loc.getResponderId())) {
                    loc.setDutyStatus(OFF_DUTY);
                    changed.add(loc);
                }
            }

            if (!changed.isEmpty()) {
                locationRepository.saveAll(changed);
            }
            log.debug("Synced {} responder locations from Auth Service", responders.size());
        } catch (Exception e) {
            log.warn("Failed to sync responder locations: {}", e.getMessage());
        }
    }

    /** PHASE 6: this responder's row (name, phone, vehicle), or null if Dispatch never saw them. */
    public ResponderLocation findLocation(UUID responderId) {
        return locationRepository.findByResponderId(responderId).orElse(null);
    }

    /**
     * Mark a responder as ON_MISSION in the local table (prevents re-dispatch).
     */
    @Transactional
    public void markResponderOnMission(UUID responderId) {
        locationRepository.findByResponderId(responderId).ifPresent(loc -> {
            loc.setDutyStatus(ON_MISSION);
            locationRepository.save(loc);
        });
    }

    /**
     * Mark a responder back to ON_DUTY in the local table.
     */
    @Transactional
    public void markResponderOnDuty(UUID responderId) {
        locationRepository.findByResponderId(responderId).ifPresent(loc -> {
            loc.setDutyStatus(ON_DUTY);
            locationRepository.save(loc);
        });
    }

    /**
     * Auth says this responder is OFF duty (shift over, phone silent, or they switched off).
     * Remove them from the pool at once instead of waiting for the next sync.
     * A responder who is ON_MISSION is left alone: they are busy, and Dispatch owns that state.
     */
    @Transactional
    public void markResponderOffDuty(UUID responderId) {
        locationRepository.findByResponderId(responderId).ifPresent(loc -> {
            if (ON_DUTY.equals(loc.getDutyStatus())) {
                loc.setDutyStatus(OFF_DUTY);
                locationRepository.save(loc);
            }
        });
    }
}
