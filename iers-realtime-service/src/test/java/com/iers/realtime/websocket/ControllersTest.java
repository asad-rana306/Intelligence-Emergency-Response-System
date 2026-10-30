package com.iers.realtime.websocket;

import com.iers.realtime.dto.CallMessage;
import com.iers.realtime.service.CallRelayService;
import com.iers.realtime.service.SnapshotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ControllersTest {

    private static final String INCIDENT = "7c1d9e22-0000-4000-8000-0000000000aa";

    @Test
    @DisplayName("A call message is handed to the relay with the real user as sender")
    void callMessage() {
        CallRelayService relay = mock(CallRelayService.class);
        CallController controller = new CallController(relay);
        CallMessage message = new CallMessage(INCIDENT, "fake", "RING", Map.of());

        controller.call(INCIDENT, message, new StompUser("u1"));

        verify(relay).relay("u1", INCIDENT, message);
    }

    @Test
    @DisplayName("An unreadable call message gives the sender a CALL_ERROR")
    void unreadable() {
        CallRelayService relay = mock(CallRelayService.class);
        CallController controller = new CallController(relay);

        controller.unreadable(new IllegalArgumentException("bad json"), new StompUser("u1"));

        verify(relay).rejectUnreadable("u1");
    }

    @Test
    @DisplayName("An unreadable call message with no known user is only logged")
    void unreadableNoUser() {
        CallRelayService relay = mock(CallRelayService.class);
        new CallController(relay).unreadable(new IllegalArgumentException("bad json"), null);
        verifyNoInteractions(relay);
    }

    @Test
    @DisplayName("/app/snapshot sends a fresh SNAPSHOT to the asking user")
    void snapshot() {
        SnapshotService snapshots = mock(SnapshotService.class);
        new SnapshotController(snapshots).snapshot(new StompUser("u1"));
        verify(snapshots).send("u1");
    }

    @Test
    @DisplayName("StompUser: same name is the same user")
    void stompUser() {
        assertThat(new StompUser("a")).isEqualTo(new StompUser("a"))
                .hasSameHashCodeAs(new StompUser("a"))
                .isNotEqualTo(new StompUser("b"));
        assertThat(new StompUser("a").getName()).isEqualTo("a");
        assertThat(new StompUser("a").toString()).contains("a");
        assertThat(new StompUser("a")).isNotEqualTo("a");
    }
}
