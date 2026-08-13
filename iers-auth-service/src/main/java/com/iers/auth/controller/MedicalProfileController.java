package com.iers.auth.controller;

import com.iers.auth.dto.request.MedicalProfileRequest;
import com.iers.auth.dto.response.MedicalProfileResponse;
import com.iers.auth.service.MedicalProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/medical-profiles")
@RequiredArgsConstructor
public class MedicalProfileController {

    private final MedicalProfileService medicalProfileService;

    @PostMapping
    public ResponseEntity<MedicalProfileResponse> create(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody MedicalProfileRequest request) {
        MedicalProfileResponse response =
                medicalProfileService.createProfile(UUID.fromString(userId), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/me")
    public ResponseEntity<MedicalProfileResponse> getMyProfile(
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(
                medicalProfileService.getProfileByUserId(UUID.fromString(userId)));
    }

    @PutMapping("/me")
    public ResponseEntity<MedicalProfileResponse> updateMyProfile(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody MedicalProfileRequest request) {
        return ResponseEntity.ok(
                medicalProfileService.updateProfile(UUID.fromString(userId), request));
    }
}
