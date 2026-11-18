package com.iers.auth.service;

import com.iers.auth.config.PushProperties;
import com.iers.auth.entity.PushToken;
import com.iers.auth.repository.PushTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PushTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Mock private PushTokenRepository repository;

    private PushTokenService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // keep at most 2 tokens per user in these tests
        service = new PushTokenService(repository, new PushProperties(2), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PushToken row(UUID user, String token) {
        return PushToken.builder().id(UUID.randomUUID()).userId(user).token(token).platform("android").build();
    }

    // ══════════════════ REGISTER ══════════════════

    @Test
    @DisplayName("register — a new token is saved (platform defaults to android)")
    void register_new() {
        when(repository.findByToken("tok-1")).thenReturn(Optional.empty());
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());

        service.register(userId, "  tok-1  ", null);

        ArgumentCaptor<PushToken> saved = ArgumentCaptor.forClass(PushToken.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getToken()).isEqualTo("tok-1");
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getPlatform()).isEqualTo("android");
    }

    @Test
    @DisplayName("register — platform is lower-cased and cut to 16 characters")
    void register_platform() {
        when(repository.findByToken("tok-1")).thenReturn(Optional.empty());
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());

        service.register(userId, "tok-1", " IOS ");

        ArgumentCaptor<PushToken> saved = ArgumentCaptor.forClass(PushToken.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPlatform()).isEqualTo("ios");
    }

    @Test
    @DisplayName("register — the same phone logs in as another user: the token moves to that user")
    void register_movesToNewUser() {
        PushToken existing = row(UUID.randomUUID(), "tok-1");
        when(repository.findByToken("tok-1")).thenReturn(Optional.of(existing));
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(existing));

        service.register(userId, "tok-1", "android");

        assertThat(existing.getUserId()).isEqualTo(userId);
        assertThat(existing.getUpdatedAt()).isEqualTo(NOW);
        verify(repository).save(existing);
    }

    @Test
    @DisplayName("register — only the newest tokens are kept")
    void register_keepsNewestOnly() {
        PushToken newest = row(userId, "t-new");
        PushToken middle = row(userId, "t-mid");
        PushToken oldest = row(userId, "t-old");
        when(repository.findByToken("t-new")).thenReturn(Optional.of(newest));
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(newest, middle, oldest));

        service.register(userId, "t-new", "android");

        verify(repository).deleteAll(List.of(oldest));
    }

    @Test
    @DisplayName("register — a blank token is refused")
    void register_blank() {
        assertThatThrownBy(() -> service.register(userId, "  ", "android"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register(userId, null, "android"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    // ══════════════════ UNREGISTER / LOOKUP / INVALIDATE ══════════════════

    @Test
    @DisplayName("unregister — removes the token of this user")
    void unregister() {
        service.unregister(userId, " tok-1 ");

        verify(repository).deleteByTokenAndUser("tok-1", userId);
    }

    @Test
    @DisplayName("unregister — blank token does nothing")
    void unregister_blank() {
        service.unregister(userId, " ");
        service.unregister(userId, null);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("tokensOf — returns the tokens, newest first")
    void tokensOf() {
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId))
                .thenReturn(List.of(row(userId, "a"), row(userId, "b")));

        assertThat(service.tokensOf(userId)).containsExactly("a", "b");
    }

    @Test
    @DisplayName("invalidate — dead tokens are removed")
    void invalidate() {
        when(repository.deleteAllByTokenIn(List.of("x", "y"))).thenReturn(2);

        assertThat(service.invalidate(List.of("x", "y"))).isEqualTo(2);
    }

    @Test
    @DisplayName("invalidate — empty or null list does nothing")
    void invalidate_empty() {
        assertThat(service.invalidate(List.of())).isZero();
        assertThat(service.invalidate(null)).isZero();

        verify(repository, never()).deleteAllByTokenIn(any());
    }
}
