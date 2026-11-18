package com.iers.auth.store;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis version of {@link OtpStore}. Works with any number of Auth instances.
 *
 * Keys (all expire by themselves):
 *   otp:code:{phone}      the code
 *   otp:tries:{phone}     wrong guesses for the current code
 *   otp:sends:{phone}     codes sent in the current window
 *   otp:cooldown:{phone}  resend cooldown
 *   otp:verified:{phone}  phone passed verification, waiting for registration
 */
@Component
@RequiredArgsConstructor
public class RedisOtpStore implements OtpStore {

    /** INCR and set the expiry in ONE step, so a crash in between can never leave a key without expiry. */
    private static final RedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    private final StringRedisTemplate redis;

    @Override
    public long incrementSendCount(String phone, Duration window) {
        return incrWithTtl("otp:sends:" + phone, window);
    }

    @Override
    public boolean startCooldown(String phone, Duration cooldown) {
        Boolean created = redis.opsForValue().setIfAbsent("otp:cooldown:" + phone, "1", cooldown);
        return Boolean.TRUE.equals(created);
    }

    @Override
    public Duration cooldownRemaining(String phone) {
        Long seconds = redis.getExpire("otp:cooldown:" + phone, TimeUnit.SECONDS);
        return (seconds == null || seconds < 0) ? Duration.ZERO : Duration.ofSeconds(seconds);
    }

    @Override
    public void saveCode(String phone, String code, Duration ttl) {
        redis.opsForValue().set("otp:code:" + phone, code, ttl);
        redis.delete("otp:tries:" + phone);
    }

    @Override
    public Optional<String> findCode(String phone) {
        return Optional.ofNullable(redis.opsForValue().get("otp:code:" + phone));
    }

    @Override
    public long incrementAttempts(String phone, Duration ttl) {
        return incrWithTtl("otp:tries:" + phone, ttl);
    }

    @Override
    public void deleteCode(String phone) {
        redis.delete("otp:code:" + phone);
        redis.delete("otp:tries:" + phone);
    }

    @Override
    public void markVerified(String phone, Duration ttl) {
        redis.opsForValue().set("otp:verified:" + phone, "1", ttl);
    }

    @Override
    public boolean consumeVerified(String phone) {
        // DEL returns true only for the caller that actually removed the key.
        return Boolean.TRUE.equals(redis.delete("otp:verified:" + phone));
    }

    private long incrWithTtl(String key, Duration ttl) {
        Long value = redis.execute(INCR_WITH_TTL, List.of(key), String.valueOf(Math.max(1, ttl.toSeconds())));
        return value == null ? 1L : value;
    }
}
