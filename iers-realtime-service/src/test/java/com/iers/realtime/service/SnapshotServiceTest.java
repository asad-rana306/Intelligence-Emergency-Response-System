package com.iers.realtime.service;

import com.iers.realtime.dto.LiveEvent;
import com.iers.realtime.store.InMemoryLiveStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SnapshotServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-29T18:30:12Z"), ZoneOffset.UTC);
    private InMemoryLiveStateStore store;
    private LivePublisher publisher;
    private SnapshotService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryLiveStateStore();
        publisher = mock(LivePublisher.class);
        service = new SnapshotService(store, publisher, clock);
    }

    @Test
    @DisplayName("A user with nothing gets a SNAPSHOT with three nulls")
    void empty() {
        LiveEvent snapshot = service.build("u1");

        assertThat(snapshot.type()).isEqualTo("SNAPSHOT");
        assertThat(snapshot.serverTime()).isEqualTo("2026-10-29T18:30:12Z");
        assertThat(snapshot.eventId()).isNotBlank();
        assertThat(snapshot.data()).containsOnlyKeys("crash", "offer", "incident");
        assertThat(snapshot.data().values()).containsOnlyNulls();
    }

    @Test
    @DisplayName("Open countdown, open offer and incident are all included")
    void everythingOpen() {
        store.putSnapshot("u1", "crash", Map.of("crashEventId", "c1", "windowEndsAt", "2026-10-29T18:30:20Z"));
        store.putSnapshot("u1", "offer", Map.of("incidentId", "i1", "dispatchExpiresAt", "2026-10-29T18:30:42Z"));
        store.putSnapshot("u1", "incident", Map.of("incidentId", "i1", "status", "DISPATCHED"));

        LiveEvent snapshot = service.build("u1");

        assertThat(snapshot.data().get("crash")).isNotNull();
        assertThat(snapshot.data().get("offer")).isNotNull();
        assertThat(snapshot.data().get("incident")).isNotNull();
    }

    @Test
    @DisplayName("A countdown or offer that already ran out is sent as null")
    void expiredIsNull() {
        store.putSnapshot("u1", "crash", Map.of("crashEventId", "c1", "windowEndsAt", "2026-10-29T18:30:00Z"));
        store.putSnapshot("u1", "offer", Map.of("incidentId", "i1", "dispatchExpiresAt", "2026-10-29T18:30:11Z"));
        store.putSnapshot("u1", "incident", Map.of("incidentId", "i1"));

        LiveEvent snapshot = service.build("u1");

        assertThat(snapshot.data().get("crash")).isNull();
        assertThat(snapshot.data().get("offer")).isNull();
        assertThat(snapshot.data().get("incident")).isNotNull();   // an incident has no countdown
    }

    @Test
    @DisplayName("A value with a missing or unreadable end time is kept")
    void unreadableEndKept() {
        store.putSnapshot("u1", "crash", Map.of("crashEventId", "c1", "windowEndsAt", "soon"));
        store.putSnapshot("u1", "offer", Map.of("incidentId", "i1"));

        LiveEvent snapshot = service.build("u1");

        assertThat(snapshot.data().get("crash")).isNotNull();
        assertThat(snapshot.data().get("offer")).isNotNull();
    }

    @Test
    @DisplayName("If the store is down the user still gets a SNAPSHOT, all null")
    void storeDown() {
        store.broken = true;

        LiveEvent snapshot = service.build("u1");

        assertThat(snapshot.data().values()).containsOnlyNulls();
    }

    @Test
    @DisplayName("send() delivers the SNAPSHOT to that user's queue")
    void send() {
        service.send("u1");

        ArgumentCaptor<LiveEvent> captor = ArgumentCaptor.forClass(LiveEvent.class);
        verify(publisher).sendEvent(eq("u1"), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("SNAPSHOT");
    }

    @Test
    @DisplayName("send() never throws, even if the push fails")
    void sendFailureSwallowed() {
        doThrow(new IllegalStateException("gone")).when(publisher).sendEvent(anyString(), any(LiveEvent.class));
        assertThatCode(() -> service.send("u1")).doesNotThrowAnyException();
    }
}
