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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Uses a small in-memory store and a fake SMS sender (no Mockito), so the tests read like the real flow.
 */
class OtpServiceTest {

    private static final String PHONE = "+923001234567";

    private FakeStore store;
    private FakeSms sms;
    private OtpService service;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        sms = new FakeSms();
        service = serviceWith(props(true, 6));
    }

    private OtpService serviceWith(OtpProperties props) {
        return new OtpService(store, sms, new PhoneNormalizer("92"), props);
    }

    private static OtpProperties props(boolean required, int codeLength) {
        return new OtpProperties(required, codeLength, Duration.ofMinutes(5), 5, 3,
                Duration.ofHours(1), Duration.ofSeconds(30), Duration.ofMinutes(10));
    }

    private String savedCode() {
        return store.codes.get(PHONE);
    }

    // ══════════════════ SEND ══════════════════

    @Test
    @DisplayName("sendCode — saves a 6 digit code and sends it by SMS")
    void sendCode_success() {
        OtpSendResponse response = service.sendCode("0300 1234567");

        assertThat(savedCode()).matches("\\d{6}");
        assertThat(sms.sentTo).containsExactly(PHONE);
        assertThat(sms.lastMessage).contains(savedCode()).contains("5 min");
        assertThat(response.getStatus()).isEqualTo("SENT");
        assertThat(response.getExpiresInSeconds()).isEqualTo(300);
        assertThat(response.getResendAfterSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("sendCode — QUEUED from the gateway is passed to the app")
    void sendCode_queued() {
        sms.next = SmsResult.queued();

        assertThat(service.sendCode(PHONE).getStatus()).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("sendCode — code length can be changed (4 digits)")
    void sendCode_codeLength() {
        serviceWith(props(true, 4)).sendCode(PHONE);

        assertThat(savedCode()).matches("\\d{4}");
    }

    @Test
    @DisplayName("sendCode — a second request inside the cooldown is refused, no SMS")
    void sendCode_cooldown() {
        service.sendCode(PHONE);

        assertThatThrownBy(() -> service.sendCode(PHONE))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(20));
        assertThat(sms.sentTo).hasSize(1);
    }

    @Test
    @DisplayName("sendCode — more than 3 codes per hour are refused")
    void sendCode_windowLimit() {
        for (int i = 0; i < 3; i++) {
            store.cooldowns.clear();
            service.sendCode(PHONE);
        }
        store.cooldowns.clear();

        assertThatThrownBy(() -> service.sendCode(PHONE))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(3600));
        assertThat(sms.sentTo).hasSize(3);
    }

    @Test
    @DisplayName("sendCode — SMS not accepted: the code is removed and an error is thrown")
    void sendCode_smsFails() {
        sms.next = SmsResult.failed("HTTP 401");

        assertThatThrownBy(() -> service.sendCode(PHONE)).isInstanceOf(SmsDeliveryException.class);
        assertThat(store.codes).isEmpty();
    }

    @Test
    @DisplayName("sendCode — bad phone number is refused before anything is sent")
    void sendCode_badPhone() {
        assertThatThrownBy(() -> service.sendCode("abc")).isInstanceOf(IllegalArgumentException.class);
        assertThat(sms.sentTo).isEmpty();
    }

    // ══════════════════ VERIFY ══════════════════

    @Test
    @DisplayName("verifyCode — correct code: ok, the code is used up, the phone is marked verified")
    void verifyCode_success() {
        service.sendCode(PHONE);

        OtpVerifyResponse response = service.verifyCode(PHONE, savedCode());

        assertThat(response.isOk()).isTrue();
        assertThat(response.getReason()).isNull();
        assertThat(store.codes).isEmpty();
        assertThat(store.verified).contains(PHONE);
    }

    @Test
    @DisplayName("verifyCode — the phone can be typed in another format")
    void verifyCode_otherPhoneFormat() {
        service.sendCode("03001234567");

        assertThat(service.verifyCode("+92 300 1234567", savedCode()).isOk()).isTrue();
    }

    @Test
    @DisplayName("verifyCode — wrong code: WRONG_CODE and the tries left")
    void verifyCode_wrong() {
        service.sendCode(PHONE);

        OtpVerifyResponse response = service.verifyCode(PHONE, wrongCode());

        assertThat(response.isOk()).isFalse();
        assertThat(response.getReason()).isEqualTo(OtpVerifyResponse.WRONG_CODE);
        assertThat(response.getRemainingAttempts()).isEqualTo(4);
        assertThat(store.verified).isEmpty();
    }

    @Test
    @DisplayName("verifyCode — 5 wrong guesses burn the code, even the right code fails after that")
    void verifyCode_tooManyAttempts() {
        service.sendCode(PHONE);
        String right = savedCode();
        for (int i = 0; i < 5; i++) {
            service.verifyCode(PHONE, wrongCode());
        }

        OtpVerifyResponse sixth = service.verifyCode(PHONE, right);

        assertThat(sixth.isOk()).isFalse();
        assertThat(sixth.getReason()).isEqualTo(OtpVerifyResponse.TOO_MANY_ATTEMPTS);
        assertThat(store.codes).isEmpty();
        assertThat(service.verifyCode(PHONE, right).getReason()).isEqualTo(OtpVerifyResponse.EXPIRED);
    }

    @Test
    @DisplayName("verifyCode — no code (never sent or expired): EXPIRED")
    void verifyCode_expired() {
        OtpVerifyResponse response = service.verifyCode(PHONE, "123456");

        assertThat(response.isOk()).isFalse();
        assertThat(response.getReason()).isEqualTo(OtpVerifyResponse.EXPIRED);
    }

    @Test
    @DisplayName("verifyCode — a new code replaces the old one and the wrong-guess counter starts again")
    void verifyCode_newCodeResetsAttempts() {
        service.sendCode(PHONE);
        for (int i = 0; i < 4; i++) {
            service.verifyCode(PHONE, wrongCode());
        }
        store.cooldowns.clear();
        service.sendCode(PHONE);

        OtpVerifyResponse response = service.verifyCode(PHONE, wrongCode());

        assertThat(response.getRemainingAttempts()).isEqualTo(4);
    }

    // ══════════════════ REQUIRE VERIFIED ══════════════════

    @Test
    @DisplayName("requireVerifiedPhone — refused when the phone was not verified")
    void requireVerified_notVerified() {
        assertThatThrownBy(() -> service.requireVerifiedPhone(PHONE))
                .isInstanceOf(PhoneNotVerifiedException.class);
    }

    @Test
    @DisplayName("requireVerifiedPhone — works once after verification, then the phone must be verified again")
    void requireVerified_usedOnce() {
        service.sendCode(PHONE);
        service.verifyCode(PHONE, savedCode());

        assertThatCode(() -> service.requireVerifiedPhone(PHONE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requireVerifiedPhone(PHONE))
                .isInstanceOf(PhoneNotVerifiedException.class);
    }

    @Test
    @DisplayName("requireVerifiedPhone — does nothing when OTP is switched off")
    void requireVerified_switchedOff() {
        OtpService off = serviceWith(props(false, 6));

        assertThatCode(() -> off.requireVerifiedPhone(PHONE)).doesNotThrowAnyException();
    }

    private String wrongCode() {
        return "000000".equals(savedCode()) ? "111111" : "000000";
    }

    // ══════════════════ FAKES ══════════════════

    private static class FakeSms implements SmsSender {
        final List<String> sentTo = new ArrayList<>();
        String lastMessage;
        SmsResult next = SmsResult.sent();

        @Override
        public SmsResult send(String toE164, String message) {
            sentTo.add(toE164);
            lastMessage = message;
            return next;
        }
    }

    private static class FakeStore implements OtpStore {
        final Map<String, String> codes = new HashMap<>();
        final Map<String, Long> tries = new HashMap<>();
        final Map<String, Long> sends = new HashMap<>();
        final Set<String> cooldowns = new HashSet<>();
        final Set<String> verified = new HashSet<>();

        @Override
        public long incrementSendCount(String phone, Duration window) {
            return sends.merge(phone, 1L, Long::sum);
        }

        @Override
        public boolean startCooldown(String phone, Duration cooldown) {
            return cooldowns.add(phone);
        }

        @Override
        public Duration cooldownRemaining(String phone) {
            return cooldowns.contains(phone) ? Duration.ofSeconds(20) : Duration.ZERO;
        }

        @Override
        public void saveCode(String phone, String code, Duration ttl) {
            codes.put(phone, code);
            tries.remove(phone);
        }

        @Override
        public Optional<String> findCode(String phone) {
            return Optional.ofNullable(codes.get(phone));
        }

        @Override
        public long incrementAttempts(String phone, Duration ttl) {
            return tries.merge(phone, 1L, Long::sum);
        }

        @Override
        public void deleteCode(String phone) {
            codes.remove(phone);
            tries.remove(phone);
        }

        @Override
        public void markVerified(String phone, Duration ttl) {
            verified.add(phone);
        }

        @Override
        public boolean consumeVerified(String phone) {
            return verified.remove(phone);
        }
    }
}
