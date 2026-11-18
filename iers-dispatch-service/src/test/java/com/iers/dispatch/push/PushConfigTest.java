package com.iers.dispatch.push;

import com.iers.dispatch.config.PushProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushConfigTest {

    private final PushConfig config = new PushConfig();

    private PushProperties props(boolean enabled, String path, String project) {
        return new PushProperties(enabled, path, project, Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("switched off: pushes are only logged")
    void disabled() {
        assertThat(config.pushSender(props(false, "/app/key.json", "p"))).isInstanceOf(LoggingPushSender.class);
    }

    @Test
    @DisplayName("no credentials or project id (local development): pushes are only logged")
    void notConfigured() {
        assertThat(config.pushSender(props(true, "", ""))).isInstanceOf(LoggingPushSender.class);
        assertThat(config.pushSender(props(true, null, null))).isInstanceOf(LoggingPushSender.class);
        assertThat(config.pushSender(props(true, "/app/key.json", ""))).isInstanceOf(LoggingPushSender.class);
    }

    @Test
    @DisplayName("a key file that cannot be read stops the start-up (better now than during an emergency)")
    void unreadableKeyFails() {
        assertThatThrownBy(() -> config.pushSender(props(true, "/no/such/firebase-key.json", "iers-a6400")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/no/such/firebase-key.json");
    }

    @Test
    @DisplayName("the log sender reports SENT")
    void loggingSender() {
        assertThat(new LoggingPushSender().send("t", PushMessage.offDuty(null))).isEqualTo(PushOutcome.SENT);
    }
}
