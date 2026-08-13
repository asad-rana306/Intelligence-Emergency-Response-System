package com.iers.auth.service;

import com.iers.auth.dto.request.UpdateDutyStatusRequest;
import com.iers.auth.dto.request.UpdateLocationRequest;
import com.iers.auth.dto.response.AvailableResponderResponse;
import com.iers.auth.dto.response.ResponderProfileResponse;
import com.iers.auth.entity.ResponderProfile;
import com.iers.auth.entity.enums.DutyStatus;
import com.iers.auth.exception.ResourceNotFoundException;
import com.iers.auth.repository.ResponderProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResponderService {

    private final ResponderProfileRepository responderProfileRepository;

    @Transactional
    public ResponderProfileResponse updateDutyStatus(UUID userId, UpdateDutyStatusRequest request) {
        ResponderProfile profile = responderProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Responder profile not found for user: " + userId));

        profile.setDutyStatus(request.getStatus());
        profile = responderProfileRepository.save(profile);

        log.info("Responder {} duty status updated to {}", userId, request.getStatus());
        return ResponderProfileResponse.from(profile);
    }

    @Transactional
    public ResponderProfileResponse updateLocation(UUID userId, UpdateLocationRequest request) {
        ResponderProfile profile = responderProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Responder profile not found for user: " + userId));

        profile.setCurrentLat(request.getLatitude());
        profile.setCurrentLng(request.getLongitude());
        profile = responderProfileRepository.save(profile);

        return ResponderProfileResponse.from(profile);
    }

    /**
     * Returns all responders with ON_DUTY status and a known GPS location.
     * Called internally by the Dispatch Service via Feign.
     */
    public List<AvailableResponderResponse> getAvailableResponders() {
        return responderProfileRepository
                .findAllByDutyStatusWithUser(DutyStatus.ON_DUTY)
                .stream()
                .map(AvailableResponderResponse::from)
                .toList();
    }

    /**
     * Update a responder's status by their user ID.
     * Called internally by Dispatch when a responder accepts/completes a mission.
     */
    @Transactional
    public void updateStatusByUserId(UUID userId, DutyStatus status) {
        ResponderProfile profile = responderProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Responder profile not found for user: " + userId));
        profile.setDutyStatus(status);
        responderProfileRepository.save(profile);
        log.info("Internal status update: responder {} → {}", userId, status);
    }
}
