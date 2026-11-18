package com.iers.auth.service;

import com.iers.auth.config.PushProperties;
import com.iers.auth.entity.PushToken;
import com.iers.auth.repository.PushTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Keeps the Firebase tokens of every user's phones. This service only STORES tokens;
 * sending the push is done by the Dispatch service, which asks for them here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushTokenService {

    private static final String DEFAULT_PLATFORM = "android";

    private final PushTokenRepository repository;
    private final PushProperties props;
    private final Clock clock;

    /** Saves or refreshes a token. A token seen again is moved to the user who sent it. */
    @Transactional
    public void register(UUID userId, String token, String platform) {
        String cleanToken = token == null ? "" : token.trim();
        if (cleanToken.isEmpty()) {
            throw new IllegalArgumentException("Push token is required");
        }
        String cleanPlatform = normalizePlatform(platform);

        PushToken row = repository.findByToken(cleanToken).orElse(null);
        if (row == null) {
            repository.save(PushToken.builder()
                    .userId(userId)
                    .token(cleanToken)
                    .platform(cleanPlatform)
                    .build());
        } else {
            row.setUserId(userId);
            row.setPlatform(cleanPlatform);
            row.setUpdatedAt(clock.instant()); // makes this token the "newest" one
            repository.save(row);
        }
        keepNewest(userId);
    }

    /** Removes one token of this user (logout). Does nothing if it is not there. */
    @Transactional
    public void unregister(UUID userId, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        repository.deleteByTokenAndUser(token.trim(), userId);
    }

    /** All tokens of a user, newest first. Used by Dispatch to send the push. */
    @Transactional(readOnly = true)
    public List<String> tokensOf(UUID userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(PushToken::getToken)
                .toList();
    }

    /** Dispatch reports tokens that Firebase rejected (app removed, token expired). */
    @Transactional
    public int invalidate(Collection<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return 0;
        }
        int removed = repository.deleteAllByTokenIn(tokens);
        log.info("Removed {} dead push token(s)", removed);
        return removed;
    }

    /** Old phones that never logged out would grow forever, so only the newest N are kept. */
    private void keepNewest(UUID userId) {
        int max = Math.max(1, props.maxTokensPerUser());
        List<PushToken> all = repository.findByUserIdOrderByUpdatedAtDesc(userId);
        if (all.size() > max) {
            repository.deleteAll(all.subList(max, all.size()));
        }
    }

    private static String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return DEFAULT_PLATFORM;
        }
        String p = platform.trim().toLowerCase(Locale.ROOT);
        return p.length() > 16 ? p.substring(0, 16) : p;
    }
}
