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
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpatialQueryService {

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
     */
    @Scheduled(fixedRate = 15000)
    @Transactional
    public void syncResponderLocations() {
        try {
            List<AvailableResponderDto> responders = authServiceClient.getAvailableResponders();

            for (AvailableResponderDto dto : responders) {
                UUID responderId = UUID.fromString(dto.getResponderId());

                ResponderLocation loc = locationRepository.findByResponderId(responderId)
                        .orElse(ResponderLocation.builder().responderId(responderId).build());

                loc.setUserName(dto.getFullName());
                loc.setVehicleId(dto.getVehicleId());
                loc.setDutyStatus("ON_DUTY");
                loc.setLatitude(dto.getLatitude());
                loc.setLongitude(dto.getLongitude());
                loc.setLastUpdated(Instant.now());

                locationRepository.save(loc);
            }

            log.debug("Synced {} responder locations from Auth Service", responders.size());
        } catch (Exception e) {
            log.warn("Failed to sync responder locations: {}", e.getMessage());
        }
    }

    /**
     * Mark a responder as ON_MISSION in the local table (prevents re-dispatch).
     */
    @Transactional
    public void markResponderOnMission(UUID responderId) {
        locationRepository.findByResponderId(responderId).ifPresent(loc -> {
            loc.setDutyStatus("ON_MISSION");
            locationRepository.save(loc);
        });
    }

    /**
     * Mark a responder back to ON_DUTY in the local table.
     */
    @Transactional
    public void markResponderOnDuty(UUID responderId) {
        locationRepository.findByResponderId(responderId).ifPresent(loc -> {
            loc.setDutyStatus("ON_DUTY");
            locationRepository.save(loc);
        });
    }
}
