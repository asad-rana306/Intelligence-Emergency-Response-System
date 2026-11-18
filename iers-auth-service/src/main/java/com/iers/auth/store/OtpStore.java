package com.iers.auth.store;

import java.time.Duration;
import java.util.Optional;

/**
 * Where OTP state lives. The production implementation is Redis (shared by all Auth
 * instances, codes expire by themselves). A test can use a simple in-memory copy.
 * Every phone number passed in is already in E.164 form.
 */
public interface OtpStore {

    /** Counts one code sent to this phone inside the window. @return the new count (1 = first). */
    long incrementSendCount(String phone, Duration window);

    /** Starts the resend cooldown. @return false if a cooldown is already running. */
    boolean startCooldown(String phone, Duration cooldown);

    /** Time left on the resend cooldown (zero if none). */
    Duration cooldownRemaining(String phone);

    /** Saves the code (replacing any old one) and resets the wrong-guess counter. */
    void saveCode(String phone, String code, Duration ttl);

    Optional<String> findCode(String phone);

    /** Counts one verify try for the current code. @return the new count. */
    long incrementAttempts(String phone, Duration ttl);

    /** Removes the code and the wrong-guess counter. */
    void deleteCode(String phone);

    /** Remembers that this phone passed verification. */
    void markVerified(String phone, Duration ttl);

    /** Uses up the "verified" mark. @return true only the first time (so one code = one account). */
    boolean consumeVerified(String phone);
}
