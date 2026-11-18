package com.iers.auth.controller;

import com.iers.auth.dto.request.InvalidateTokensRequest;
import com.iers.auth.dto.response.AvailableResponderResponse;
import com.iers.auth.dto.response.EmergencyContactResponse;
import com.iers.auth.dto.response.MedicalProfileResponse;
import com.iers.auth.entity.enums.DutyStatus;
import com.iers.auth.service.EmergencyContactService;
import com.iers.auth.service.MedicalProfileService;
import com.iers.auth.service.PushTokenService;
import com.iers.auth.service.ResponderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Internal API endpoints consumed by other microservices (primarily Dispatch).
 * These are routed through the API Gateway but are NOT intended for end-user clients.
 *
 * In production, restrict access via network policies or a service-mesh sidecar.
 * For FYP, the gateway routes /internal/** and we trust intra-cluster traffic.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalApiController {

    private final MedicalProfileService medicalProfileService;
    private final EmergencyContactService contactService;
    private final ResponderService responderService;
    private final PushTokenService pushTokenService;

    /**
     * Dispatch Service calls this to get the crash victim's medical info.
     */
    @GetMapping("/users/{userId}/medical-profile")
    public ResponseEntity<MedicalProfileResponse> getMedicalProfile(
            @PathVariable UUID userId) {
        return ResponseEntity.ok(medicalProfileService.getProfileByUserId(userId));
    }

    /**
     * Dispatch Service calls this to get the victim's emergency contacts for SMS alerts.
     * Phone numbers are already in international form (+92...), ready for the SMS gateway.
     */
    @GetMapping("/users/{userId}/emergency-contacts")
    public ResponseEntity<List<EmergencyContactResponse>> getEmergencyContacts(
            @PathVariable UUID userId) {
        return ResponseEntity.ok(contactService.getContactsByUserId(userId));
    }

    /**
     * Dispatch Service calls this to get all on-duty responders with known GPS positions
     * (shift not finished, heartbeat recent).
     */
    @GetMapping("/responders/available")
    public ResponseEntity<List<AvailableResponderResponse>> getAvailableResponders() {
        return ResponseEntity.ok(responderService.getAvailableResponders());
    }

    /**
     * Dispatch Service calls this to update a responder's status
     * (e.g., ON_MISSION when they accept, ON_DUTY when incident resolves).
     */
    @PutMapping("/responders/{userId}/status")
    public ResponseEntity<Void> updateResponderStatus(
            @PathVariable UUID userId,
            @RequestParam DutyStatus status) {
        responderService.updateStatusByUserId(userId, status);
        return ResponseEntity.noContent().build();
    }

    /**
     * Dispatch Service calls this to get the Firebase tokens of a user's phones (newest first).
     */
    @GetMapping("/users/{userId}/push-tokens")
    public ResponseEntity<List<String>> getPushTokens(@PathVariable UUID userId) {
        return ResponseEntity.ok(pushTokenService.tokensOf(userId));
    }

    /**
     * Dispatch Service reports tokens that Firebase rejected, so we stop keeping them.
     */
    @PostMapping("/push-tokens/invalidate")
    public ResponseEntity<Void> invalidatePushTokens(@Valid @RequestBody InvalidateTokensRequest request) {
        pushTokenService.invalidate(request.getTokens());
        return ResponseEntity.noContent().build();
    }
}
