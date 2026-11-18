package com.iers.auth.controller;

import com.iers.auth.dto.request.OtpSendRequest;
import com.iers.auth.dto.request.OtpVerifyRequest;
import com.iers.auth.dto.response.OtpSendResponse;
import com.iers.auth.dto.response.OtpVerifyResponse;
import com.iers.auth.service.OtpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * SMS code for a phone number. Both endpoints are public (the user has no account yet);
 * the Gateway lists them as open paths and rate-limits them by IP.
 */
@RestController
@RequestMapping("/auth/otp")
@RequiredArgsConstructor
public class OtpController {

    private final OtpService otpService;

    @PostMapping("/send")
    public ResponseEntity<OtpSendResponse> send(@Valid @RequestBody OtpSendRequest request) {
        return ResponseEntity.ok(otpService.sendCode(request.getPhone()));
    }

    /** Always 200. Read {@code ok} and {@code reason} in the body. */
    @PostMapping("/verify")
    public ResponseEntity<OtpVerifyResponse> verify(@Valid @RequestBody OtpVerifyRequest request) {
        return ResponseEntity.ok(otpService.verifyCode(request.getPhone(), request.getCode()));
    }
}
