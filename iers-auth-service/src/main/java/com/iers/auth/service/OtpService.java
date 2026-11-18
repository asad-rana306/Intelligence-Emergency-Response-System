package com.iers.auth.service;

import com.iers.auth.config.OtpProperties;
import com.iers.auth.dto.response.OtpSendResponse;
import com.iers.auth.dto.response.OtpVerifyResponse;
import com.iers.auth.exception.PhoneNotVerifiedException;
import com.iers.auth.exception.SmsDeliveryException;
import com.iers.auth.exception.TooManyRequestsException;
import com.iers.auth.sms.SmsResult;
import com.iers.auth.sms.SmsSender;
import com.iers.auth.store.OtpStore;
import com.iers.auth.util.PhoneNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Optional;

/**
 * SMS one-time codes.
 *
 * Flow: sendCode -> user types the code -> verifyCode -> requireVerifiedPhone (inside register).
 *
 * Protection (all values come from {@link OtpProperties}):
 *   - a cooldown between two codes for the same phone;
 *   - a limit of codes per phone per window (protects your SIM / SMS credit);
 *   - a limit of wrong guesses per code (a 6-digit code can not be brute-forced);
 *   - the "verified" mark can be used once, by one registration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private final OtpStore store;
    private final SmsSender smsSender;
    private final PhoneNormalizer phones;
    private final OtpProperties props;

    private final SecureRandom random = new SecureRandom();

    public OtpSendResponse sendCode(String rawPhone) {
        String phone = phones.normalize(rawPhone);

        // 1. Cooldown. Checked first, so a double tap does not use up the hourly allowance.
        if (!store.startCooldown(phone, props.resendCooldown())) {
            throw new TooManyRequestsException("Please wait a moment before asking for another code",
                    store.cooldownRemaining(phone).toSeconds());
        }
        // 2. Allowance per window.
        long sent = store.incrementSendCount(phone, props.sendWindow());
        if (sent > props.maxSendsPerWindow()) {
            throw new TooManyRequestsException("Too many codes were requested for this number. Try again later",
                    props.sendWindow().toSeconds());
        }

        String code = newCode();
        store.saveCode(phone, code, props.codeTtl());

        long minutes = Math.max(1, props.codeTtl().toMinutes());
        SmsResult result = smsSender.send(phone,
                "Your IERS verification code is " + code + ". It expires in " + minutes
                        + " min. Do not share it with anyone.");
        if (!result.accepted()) {
            store.deleteCode(phone);
            log.error("OTP SMS was not accepted: {}", result.detail());
            throw new SmsDeliveryException("We could not send the SMS. Please try again in a moment");
        }

        return OtpSendResponse.builder()
                .status(result.status().name())
                .expiresInSeconds(props.codeTtl().toSeconds())
                .resendAfterSeconds(props.resendCooldown().toSeconds())
                .build();
    }

    public OtpVerifyResponse verifyCode(String rawPhone, String submittedCode) {
        String phone = phones.normalize(rawPhone);

        Optional<String> expected = store.findCode(phone);
        if (expected.isEmpty()) {
            return OtpVerifyResponse.failed(OtpVerifyResponse.EXPIRED, 0);
        }

        long attempts = store.incrementAttempts(phone, props.codeTtl());
        if (attempts > props.maxVerifyAttempts()) {
            store.deleteCode(phone);
            return OtpVerifyResponse.failed(OtpVerifyResponse.TOO_MANY_ATTEMPTS, 0);
        }

        if (!sameText(expected.get(), submittedCode)) {
            int left = (int) Math.max(0, props.maxVerifyAttempts() - attempts);
            return OtpVerifyResponse.failed(OtpVerifyResponse.WRONG_CODE, left);
        }

        store.deleteCode(phone);
        store.markVerified(phone, props.verifiedTtl());
        return OtpVerifyResponse.verified();
    }

    /**
     * Called by registration with the already normalized phone. Uses up the "verified" mark.
     * Does nothing when auth.otp.required-on-register is false.
     */
    public void requireVerifiedPhone(String normalizedPhone) {
        if (!props.requiredOnRegister()) {
            return;
        }
        if (!store.consumeVerified(normalizedPhone)) {
            throw new PhoneNotVerifiedException("Phone number is not verified. Please request a code first");
        }
    }

    private String newCode() {
        int length = Math.min(9, Math.max(4, props.codeLength()));
        int bound = 1;
        for (int i = 0; i < length; i++) {
            bound *= 10;
        }
        return String.format("%0" + length + "d", random.nextInt(bound));
    }

    /** Compares in constant time, so the answer does not leak how many digits matched. */
    private static boolean sameText(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.trim().getBytes(StandardCharsets.UTF_8));
    }
}
