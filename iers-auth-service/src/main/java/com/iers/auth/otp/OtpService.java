package com.iers.auth.otp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * One-time password (OTP) over SMS.
 *
 *  - Code: 6 digits.
 *  - Lives 5 minutes in Redis.
 *  - Max 5 wrong tries, then the code is burned.
 *  - Max 3 sends per phone per hour (to stop abuse of your SIM credit).
 *
 * The Flutter app flow:
 *   1) call POST /auth/otp/send  { phone }
 *   2) user types the 6 digits
 *   3) call POST /auth/otp/verify { phone, code } -> ok=true/false
 *   4) if ok, continue to /auth/register with that same phone
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration SEND_WINDOW = Duration.ofHours(1);
    private static final int MAX_SENDS_PER_WINDOW = 3;
    private static final int MAX_WRONG_TRIES = 5;

    private final StringRedisTemplate redis;
    private final SimGateClient sms;
    private final SecureRandom rng = new SecureRandom();

    /** @return true if the SMS was sent (or already queued), false on rate limit or gateway failure. */
    public SendResult send(String phoneE164) {
        String phone = clean(phoneE164);
        if (phone == null) return SendResult.BAD_PHONE;

        String sendsKey = "otp:sends:" + phone;
        Long sends = redis.opsForValue().increment(sendsKey);
        if (sends == null) sends = 1L;
        if (sends == 1L) redis.expire(sendsKey, SEND_WINDOW);
        if (sends > MAX_SENDS_PER_WINDOW) {
            log.warn("OTP rate limit hit for {}", phone);
            return SendResult.RATE_LIMITED;
        }

        String code = String.format("%06d", rng.nextInt(1_000_000));
        redis.opsForValue().set("otp:code:" + phone, code, CODE_TTL);
        redis.delete("otp:tries:" + phone);

        boolean ok = sms.send(phone, "Your IERS verification code is " + code
                + ". It expires in 5 minutes. Do not share this code.");
        return ok ? SendResult.SENT : SendResult.SMS_FAILED;
    }

    /** @return true if the code matches. On success the code is deleted. */
    public boolean verify(String phoneE164, String code) {
        String phone = clean(phoneE164);
        if (phone == null || code == null) return false;

        String triesKey = "otp:tries:" + phone;
        Long tries = redis.opsForValue().increment(triesKey);
        if (tries == null) tries = 1L;
        if (tries == 1L) redis.expire(triesKey, CODE_TTL);
        if (tries > MAX_WRONG_TRIES) {
            redis.delete("otp:code:" + phone);
            log.warn("OTP burned after too many tries for {}", phone);
            return false;
        }

        String expected = redis.opsForValue().get("otp:code:" + phone);
        if (expected == null) return false;
        boolean match = expected.equals(code.trim());
        if (match) {
            redis.delete("otp:code:" + phone);
            redis.delete(triesKey);
            // mark as verified for 10 minutes so register can trust it
            redis.opsForValue().set("otp:verified:" + phone, "1", Duration.ofMinutes(10));
        }
        return match;
    }

    /** Used by AuthService.register to require a verified phone. */
    public boolean isPhoneVerified(String phoneE164) {
        String phone = clean(phoneE164);
        if (phone == null) return false;
        return "1".equals(redis.opsForValue().get("otp:verified:" + phone));
    }

    /** Remove the verified mark after registration finishes. */
    public void consumeVerification(String phoneE164) {
        String phone = clean(phoneE164);
        if (phone != null) redis.delete("otp:verified:" + phone);
    }

    /** Only keep + and digits. Must look like E.164: + then 8–15 digits. */
    private static String clean(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[^0-9+]", "");
        if (!s.startsWith("+")) return null;
        if (s.length() < 9 || s.length() > 16) return null;
        return s;
    }

    public enum SendResult { SENT, SMS_FAILED, RATE_LIMITED, BAD_PHONE }
}
