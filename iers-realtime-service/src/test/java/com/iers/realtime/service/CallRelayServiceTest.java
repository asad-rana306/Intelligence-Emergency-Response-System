package com.iers.realtime.service;

import com.iers.realtime.dto.CallMessage;
import com.iers.realtime.dto.LiveEvent;
import com.iers.realtime.service.CallRelayService.Outcome;
import com.iers.realtime.store.InMemoryLiveStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Calls go between exactly two people: the driver and the assigned responder of one incident. */
class CallRelayServiceTest {

    static final String DRIVER = "a1b2c3d4-0000-4000-8000-000000000001";
    static final String RESPONDER = "e5f6a7b8-0000-4000-8000-000000000002";
    static final String STRANGER = "c9d0e1f2-0000-4000-8000-000000000003";
    static final String INCIDENT = "7c1d9e22-0000-4000-8000-0000000000aa";

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-29T18:30:12Z"), ZoneOffset.UTC);
    private InMemoryLiveStateStore store;
    private LivePublisher publisher;
    private SimpUserRegistry users;
    private CallRelayService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryLiveStateStore();
        publisher = mock(LivePublisher.class);
        users = mock(SimpUserRegistry.class);
        service = new CallRelayService(store, publisher, users, clock);

        store.putLink(INCIDENT, Map.of("driverId", DRIVER, "responderId", RESPONDER, "status", "ACCEPTED"));
        when(users.getUser(DRIVER)).thenReturn(mock(SimpUser.class));
        when(users.getUser(RESPONDER)).thenReturn(mock(SimpUser.class));
    }

    private CallMessage message(String kind) {
        return new CallMessage(INCIDENT, "someone-else-pretending", kind, Map.of("sdp", "v=0"));
    }

    private String errorReason() {
        ArgumentCaptor<LiveEvent> captor = ArgumentCaptor.forClass(LiveEvent.class);
        verify(publisher).sendEvent(anyString(), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("CALL_ERROR");
        return (String) captor.getValue().data().get("reason");
    }

    @Test
    @DisplayName("Driver to responder: relayed, and 'from' is the real sender, not what the phone wrote")
    void driverToResponder() {
        Outcome outcome = service.relay(DRIVER, INCIDENT, message("OFFER"));

        assertThat(outcome).isEqualTo(Outcome.SENT);
        ArgumentCaptor<CallMessage> captor = ArgumentCaptor.forClass(CallMessage.class);
        verify(publisher).sendCall(eq(RESPONDER), captor.capture());
        assertThat(captor.getValue().from()).isEqualTo(DRIVER);
        assertThat(captor.getValue().kind()).isEqualTo("OFFER");
        assertThat(captor.getValue().incidentId()).isEqualTo(INCIDENT);
        assertThat(captor.getValue().payload()).containsEntry("sdp", "v=0");
        verify(publisher, never()).sendEvent(anyString(), any(LiveEvent.class));
    }

    @Test
    @DisplayName("Responder to driver: relayed")
    void responderToDriver() {
        assertThat(service.relay(RESPONDER, INCIDENT, message("ANSWER"))).isEqualTo(Outcome.SENT);
        verify(publisher).sendCall(eq(DRIVER), any(CallMessage.class));
    }

    @Test
    @DisplayName("Kind is read in any case and the incident ID in any case")
    void caseInsensitive() {
        assertThat(service.relay(DRIVER, INCIDENT.toUpperCase(), message("ring"))).isEqualTo(Outcome.SENT);
        verify(publisher).sendCall(eq(RESPONDER), any(CallMessage.class));
    }

    @Test
    @DisplayName("A stranger gets NOT_PARTICIPANT and nothing is relayed")
    void stranger() {
        assertThat(service.relay(STRANGER, INCIDENT, message("RING"))).isEqualTo(Outcome.NOT_PARTICIPANT);

        verify(publisher, never()).sendCall(anyString(), any(CallMessage.class));
        assertThat(errorReason()).isEqualTo("NOT_PARTICIPANT");
    }

    @Test
    @DisplayName("An unknown incident gets INCIDENT_NOT_ACTIVE")
    void unknownIncident() {
        store.links.clear();
        assertThat(service.relay(DRIVER, INCIDENT, message("RING"))).isEqualTo(Outcome.INCIDENT_NOT_ACTIVE);
        assertThat(errorReason()).isEqualTo("INCIDENT_NOT_ACTIVE");
    }

    @Test
    @DisplayName("No call before a responder has accepted")
    void notAcceptedYet() {
        store.links.clear();
        store.putLink(INCIDENT, Map.of("driverId", DRIVER, "status", "DISPATCHING"));

        assertThat(service.relay(DRIVER, INCIDENT, message("RING"))).isEqualTo(Outcome.INCIDENT_NOT_ACTIVE);
        verify(publisher, never()).sendCall(anyString(), any(CallMessage.class));
    }

    @Test
    @DisplayName("Calls work in ARRIVED and EN_ROUTE_HOSPITAL too")
    void laterStates() {
        for (String status : new String[]{"ARRIVED", "EN_ROUTE_HOSPITAL"}) {
            store.putLink(INCIDENT, Map.of("status", status));
            assertThat(service.relay(DRIVER, INCIDENT, message("RING"))).isEqualTo(Outcome.SENT);
        }
    }

    @Test
    @DisplayName("Once the incident is finished the link is gone, so a call is refused")
    void afterResolved() {
        store.removeLink(INCIDENT);
        assertThat(service.relay(RESPONDER, INCIDENT, message("RING"))).isEqualTo(Outcome.INCIDENT_NOT_ACTIVE);
    }

    @Test
    @DisplayName("A link with a wrong status (for example RESOLVED) is refused")
    void wrongStatus() {
        store.putLink(INCIDENT, Map.of("status", "RESOLVED"));
        assertThat(service.relay(DRIVER, INCIDENT, message("RING"))).isEqualTo(Outcome.INCIDENT_NOT_ACTIVE);
    }

    @Test
    @DisplayName("Unknown kind, missing kind, bad incident ID and missing message are all BAD_MESSAGE")
    void badMessages() {
        assertThat(service.relay(DRIVER, INCIDENT, message("SHOUT"))).isEqualTo(Outcome.BAD_MESSAGE);
        assertThat(service.relay(DRIVER, INCIDENT, message(null))).isEqualTo(Outcome.BAD_MESSAGE);
        assertThat(service.relay(DRIVER, "1", message("RING"))).isEqualTo(Outcome.BAD_MESSAGE);
        assertThat(service.relay(DRIVER, INCIDENT, null)).isEqualTo(Outcome.BAD_MESSAGE);
        verify(publisher, never()).sendCall(anyString(), any(CallMessage.class));
    }

    @Test
    @DisplayName("The other phone is not connected: the sender is told PEER_OFFLINE")
    void peerOffline() {
        when(users.getUser(RESPONDER)).thenReturn(null);

        assertThat(service.relay(DRIVER, INCIDENT, message("RING"))).isEqualTo(Outcome.PEER_OFFLINE);

        verify(publisher, never()).sendCall(anyString(), any(CallMessage.class));
        assertThat(errorReason()).isEqualTo("PEER_OFFLINE");
    }

    @Test
    @DisplayName("HANGUP is always sent, even if the other phone is gone (no error)")
    void hangupToOfflinePeer() {
        when(users.getUser(RESPONDER)).thenReturn(null);

        assertThat(service.relay(DRIVER, INCIDENT, message("HANGUP"))).isEqualTo(Outcome.SENT);

        verify(publisher).sendCall(eq(RESPONDER), any(CallMessage.class));
        verify(publisher, never()).sendEvent(anyString(), any(LiveEvent.class));
    }

    @Test
    @DisplayName("If the store is down a call is refused, not crashed")
    void storeDown() {
        store.broken = true;
        assertThat(service.relay(DRIVER, INCIDENT, message("RING"))).isEqualTo(Outcome.INCIDENT_NOT_ACTIVE);
    }

    @Test
    @DisplayName("An unreadable message gives the sender a CALL_ERROR with BAD_MESSAGE")
    void unreadable() {
        service.rejectUnreadable(DRIVER);
        assertThat(errorReason()).isEqualTo("BAD_MESSAGE");
    }

    @Test
    @DisplayName("A failing error push does not throw")
    void errorPushFails() {
        org.mockito.Mockito.doThrow(new IllegalStateException("gone"))
                .when(publisher).sendEvent(anyString(), any(LiveEvent.class));
        assertThat(service.relay(STRANGER, INCIDENT, message("RING"))).isEqualTo(Outcome.NOT_PARTICIPANT);
    }
}
