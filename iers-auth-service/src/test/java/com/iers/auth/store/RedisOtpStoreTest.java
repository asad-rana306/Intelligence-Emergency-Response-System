package com.iers.auth.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisOtpStoreTest {

    private static final String PHONE = "+923001234567";

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;

    private RedisOtpStore store;

    @BeforeEach
    void setUp() {
        store = new RedisOtpStore(redis);
    }

    @Test
    @DisplayName("startCooldown — true only for the first caller")
    void startCooldown() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent("otp:cooldown:" + PHONE, "1", Duration.ofSeconds(30)))
                .thenReturn(true, false, null);

        assertThat(store.startCooldown(PHONE, Duration.ofSeconds(30))).isTrue();
        assertThat(store.startCooldown(PHONE, Duration.ofSeconds(30))).isFalse();
        assertThat(store.startCooldown(PHONE, Duration.ofSeconds(30))).isFalse();
    }

    @Test
    @DisplayName("cooldownRemaining — seconds left, or zero when there is none")
    void cooldownRemaining() {
        when(redis.getExpire("otp:cooldown:" + PHONE, TimeUnit.SECONDS)).thenReturn(12L, -2L, null);

        assertThat(store.cooldownRemaining(PHONE)).isEqualTo(Duration.ofSeconds(12));
        assertThat(store.cooldownRemaining(PHONE)).isEqualTo(Duration.ZERO);
        assertThat(store.cooldownRemaining(PHONE)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("saveCode — stores the code with its lifetime and resets the wrong-guess counter")
    void saveCode() {
        when(redis.opsForValue()).thenReturn(values);

        store.saveCode(PHONE, "123456", Duration.ofMinutes(5));

        verify(values).set("otp:code:" + PHONE, "123456", Duration.ofMinutes(5));
        verify(redis).delete("otp:tries:" + PHONE);
    }

    @Test
    @DisplayName("findCode — empty when there is no code")
    void findCode() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("otp:code:" + PHONE)).thenReturn("654321", (String) null);

        assertThat(store.findCode(PHONE)).isEqualTo(Optional.of("654321"));
        assertThat(store.findCode(PHONE)).isEmpty();
    }

    @Test
    @DisplayName("deleteCode — removes the code and the counter")
    void deleteCode() {
        store.deleteCode(PHONE);

        verify(redis).delete("otp:code:" + PHONE);
        verify(redis).delete("otp:tries:" + PHONE);
    }

    @Test
    @DisplayName("consumeVerified — true only for the caller that removed the mark")
    void consumeVerified() {
        when(redis.delete("otp:verified:" + PHONE)).thenReturn(true, false, null);

        assertThat(store.consumeVerified(PHONE)).isTrue();
        assertThat(store.consumeVerified(PHONE)).isFalse();
        assertThat(store.consumeVerified(PHONE)).isFalse();
    }

    @Test
    @DisplayName("incrementSendCount — one atomic script call with the window in seconds")
    void incrementSendCount() {
        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of("otp:sends:" + PHONE)), eq("3600"))).thenReturn(2L);

        assertThat(store.incrementSendCount(PHONE, Duration.ofHours(1))).isEqualTo(2L);
    }

    @Test
    @DisplayName("incrementAttempts — one atomic script call with the code lifetime in seconds")
    void incrementAttempts() {
        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of("otp:tries:" + PHONE)), eq("300"))).thenReturn(3L);

        assertThat(store.incrementAttempts(PHONE, Duration.ofMinutes(5))).isEqualTo(3L);
    }
}
