package com.iers.auth.otp;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Public OTP endpoints. The Gateway must allow /auth/otp/** without JWT.
 *
 *  POST /auth/otp/send    { "phone": "+923001234567" }         -> 200 {"status":"SENT"} | 429 | 400
 *  POST /auth/otp/verify  { "phone": "...", "code": "123456" } -> 200 {"ok":true|false}
 */
@RestController
@RequestMapping("/auth/otp")
@RequiredArgsConstructor
public class OtpController {

    private final OtpService otp;

    @PostMapping("/send")
    public ResponseEntity<?> send(@Valid @RequestBody SendRequest req) {
        OtpService.SendResult r = otp.send(req.getPhone());
        return switch (r) {
            case SENT        -> ResponseEntity.ok(java.util.Map.of("status", "SENT"));
            case SMS_FAILED  -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                                    .body(java.util.Map.of("error", "SMS gateway failed"));
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                    .body(java.util.Map.of("error", "Too many codes, try again later"));
            case BAD_PHONE   -> ResponseEntity.badRequest()
                                    .body(java.util.Map.of("error", "Phone must be in +9230... (E.164) form"));
        };
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@Valid @RequestBody VerifyRequest req) {
        boolean ok = otp.verify(req.getPhone(), req.getCode());
        return ResponseEntity.ok(java.util.Map.of("ok", ok));
    }

    @Data public static class SendRequest {
        @NotBlank private String phone;
    }

    @Data public static class VerifyRequest {
        @NotBlank private String phone;
        @NotBlank @Pattern(regexp = "\\d{6}") @Size(min = 6, max = 6) private String code;
    }
}
