package com.iers.realtime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.realtime.dto.KafkaEvent;
import com.iers.realtime.dto.LiveEvent;
import com.iers.realtime.store.InMemoryLiveStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** The fan-out rules: who gets which event, and what the snapshot remembers. */
class FanOutServiceTest {

    static final String DRIVER = "a1b2c3d4-0000-4000-8000-000000000001";
    static final String RESPONDER = "e5f6a7b8-0000-4000-8000-000000000002";
    static final String OTHER = "c9d0e1f2-0000-4000-8000-000000000003";
    static final String INCIDENT = "7c1d9e22-0000-4000-8000-0000000000aa";
    static final String CRASH = "3f2b8c1e-0000-4000-8000-0000000000bb";
    static final String RECENT = "2026-10-29T18:30:10Z";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-29T18:30:12Z"), ZoneOffset.UTC);

    private LivePublisher publisher;
    private InMemoryLiveStateStore store;
    private FanOutService fanOut;

    @BeforeEach
    void setUp() {
        publisher = mock(LivePublisher.class);
        store = new InMemoryLiveStateStore();
        fanOut = new FanOutService(publisher, store, clock, 120);
    }

    private KafkaEvent event(String json) {
        try {
            return KafkaEvent.parse(mapper, json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String crashJson(String type, String extra) {
        return "{\"eventType\":\"" + type + "\",\"crashEventId\":\"" + CRASH + "\",\"driverId\":\"" + DRIVER
                + "\",\"timestamp\":\"" + RECENT + "\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private String incidentJson(String type, String responderId, String data) {
        return "{\"eventType\":\"" + type + "\",\"incidentId\":\"" + INCIDENT + "\",\"crashEventId\":\"" + CRASH
                + "\",\"driverId\":\"" + DRIVER + "\",\"responderId\":" + (responderId == null ? "null" : "\"" + responderId + "\"")
                + ",\"timestamp\":\"" + RECENT + "\",\"data\":" + data + "}";
    }

    private LiveEvent pushedTo(String user) {
        ArgumentCaptor<LiveEvent> captor = ArgumentCaptor.forClass(LiveEvent.class);
        verify(publisher).sendEvent(eq(user), captor.capture());
        return captor.getValue();
    }

    // ───────────────────────── crash-events ─────────────────────────

    @Test
    @DisplayName("CRASH_PENDING goes to the driver only, and the countdown is remembered")
    void crashPending() {
        fanOut.handle(event(crashJson("CRASH_PENDING",
                "\"deviceId\":\"DEV-001\",\"source\":\"HTTP\",\"windowEndsAt\":\"2026-10-29T18:30:20Z\"")));

        LiveEvent live = pushedTo(DRIVER);
        assertThat(live.type()).isEqualTo("CRASH_PENDING");
        assertThat(live.serverTime()).isEqualTo("2026-10-29T18:30:12Z");
        assertThat(live.data()).containsEntry("crashEventId", CRASH)
                .containsEntry("windowEndsAt", "2026-10-29T18:30:20Z")
                .containsEntry("source", "HTTP");
        verifyNoMoreInteractions(publisher);
        assertThat(store.slot(DRIVER, "crash")).containsEntry("crashEventId", CRASH);
    }

    @Test
    @DisplayName("A crash with no driver (device not paired) tells nobody")
    void crashWithoutDriver() {
        fanOut.handle(event("{\"eventType\":\"CRASH_PENDING\",\"crashEventId\":\"" + CRASH
                + "\",\"driverId\":null,\"timestamp\":\"" + RECENT + "\"}"));
        verifyNoInteractions(publisher);
        assertThat(store.snapshots).isEmpty();
    }

    @Test
    @DisplayName("CRASH_DISMISSED goes to the driver with the reason and clears the countdown")
    void crashDismissed() {
        store.putSnapshot(DRIVER, "crash", Map.of("crashEventId", CRASH));

        fanOut.handle(event(crashJson("CRASH_DISMISSED", "\"reason\":\"CANCELLED_BY_USER\"")));

        LiveEvent live = pushedTo(DRIVER);
        assertThat(live.type()).isEqualTo("CRASH_DISMISSED");
        assertThat(live.data()).containsEntry("reason", "CANCELLED_BY_USER").containsEntry("crashEventId", CRASH);
        assertThat(store.slot(DRIVER, "crash")).isNull();
    }

    @Test
    @DisplayName("CRASH_DETECTED is not shown to phones; it only clears the countdown")
    void crashDetected() {
        store.putSnapshot(DRIVER, "crash", Map.of("crashEventId", CRASH));

        fanOut.handle(event(crashJson("CRASH_DETECTED", "\"priorityScore\":4")));

        verifyNoInteractions(publisher);
        assertThat(store.slot(DRIVER, "crash")).isNull();
    }

    @Test
    @DisplayName("CRASH_CANCELLED (late cancel) removes the driver's incident and its call link, no message")
    void lateCancel() {
        store.putSnapshot(DRIVER, "incident", Map.of("incidentId", INCIDENT, "crashEventId", CRASH));
        store.putLink(INCIDENT, Map.of("driverId", DRIVER));

        fanOut.handle(event(crashJson("CRASH_CANCELLED", "\"reason\":\"LATE_CANCEL\"")));

        verifyNoInteractions(publisher);
        assertThat(store.slot(DRIVER, "incident")).isNull();
        assertThat(store.links).doesNotContainKey(INCIDENT);
    }

    @Test
    @DisplayName("CRASH_CANCELLED for another crash leaves the driver's incident alone")
    void lateCancelOtherCrash() {
        store.putSnapshot(DRIVER, "incident", Map.of("incidentId", INCIDENT, "crashEventId", "some-other-crash"));

        fanOut.handle(event(crashJson("CRASH_CANCELLED", "")));

        assertThat(store.slot(DRIVER, "incident")).isNotNull();
    }

    // ───────────────────────── incident-events ─────────────────────────

    @Test
    @DisplayName("INCIDENT_CREATED goes to the driver, starts the incident slot and the call link")
    void incidentCreated() {
        store.putSnapshot(DRIVER, "crash", Map.of("crashEventId", CRASH));

        fanOut.handle(event(incidentJson("INCIDENT_CREATED", null,
                "{\"incidentId\":\"" + INCIDENT + "\",\"crashEventId\":\"" + CRASH + "\"}")));

        LiveEvent live = pushedTo(DRIVER);
        assertThat(live.data()).containsEntry("incidentId", INCIDENT).containsEntry("crashEventId", CRASH);
        verifyNoMoreInteractions(publisher);
        assertThat(store.slot(DRIVER, "incident")).containsEntry("status", "DISPATCHING")
                .containsEntry("incidentId", INCIDENT);
        assertThat(store.slot(DRIVER, "crash")).isNull();
        assertThat(store.links.get(INCIDENT)).containsEntry("driverId", DRIVER).containsEntry("status", "DISPATCHING");
    }

    @Test
    @DisplayName("INCIDENT_OFFERED goes to the offered responder only, with the medical info, and is remembered")
    void offered() {
        fanOut.handle(event(incidentJson("INCIDENT_OFFERED", RESPONDER,
                "{\"incidentId\":\"" + INCIDENT + "\",\"dispatchExpiresAt\":\"2026-10-29T18:30:42Z\","
                        + "\"driverName\":\"Ali\",\"gpsLat\":31.5,\"gpsLng\":74.3,\"priorityScore\":4,"
                        + "\"bloodType\":\"O+\",\"allergies\":\"Penicillin\",\"attempt\":1}")));

        LiveEvent live = pushedTo(RESPONDER);
        assertThat(live.type()).isEqualTo("INCIDENT_OFFERED");
        assertThat(live.data()).containsEntry("bloodType", "O+").containsEntry("attempt", 1)
                .containsEntry("dispatchExpiresAt", "2026-10-29T18:30:42Z");
        verifyNoMoreInteractions(publisher);
        assertThat(store.slot(RESPONDER, "offer")).containsEntry("incidentId", INCIDENT);
        assertThat(store.snapshots).doesNotContainKey(DRIVER);   // the driver never sees the offer
    }

    @Test
    @DisplayName("INCIDENT_OFFER_EXPIRED goes to that responder and removes their offer")
    void offerExpired() {
        store.putSnapshot(RESPONDER, "offer", Map.of("incidentId", INCIDENT));

        fanOut.handle(event(incidentJson("INCIDENT_OFFER_EXPIRED", RESPONDER, "{\"incidentId\":\"" + INCIDENT + "\"}")));

        assertThat(pushedTo(RESPONDER).type()).isEqualTo("INCIDENT_OFFER_EXPIRED");
        assertThat(store.slot(RESPONDER, "offer")).isNull();
    }

    @Test
    @DisplayName("An expired offer of an old incident does not remove a newer offer")
    void offerExpiredOldIncident() {
        store.putSnapshot(RESPONDER, "offer", Map.of("incidentId", "a-newer-incident"));

        fanOut.handle(event(incidentJson("INCIDENT_OFFER_EXPIRED", RESPONDER, "{\"incidentId\":\"" + INCIDENT + "\"}")));

        assertThat(store.slot(RESPONDER, "offer")).containsEntry("incidentId", "a-newer-incident");
    }

    @Test
    @DisplayName("INCIDENT_ACCEPTED goes to the driver with the responder; the responder's offer becomes an incident")
    void accepted() {
        store.putSnapshot(RESPONDER, "offer", Map.of("incidentId", INCIDENT));

        fanOut.handle(event(incidentJson("INCIDENT_ACCEPTED", RESPONDER,
                "{\"incidentId\":\"" + INCIDENT + "\",\"responder\":{\"name\":\"Bilal\",\"phone\":\"+92300\",\"vehicleId\":\"AMB-12\"}}")));

        LiveEvent live = pushedTo(DRIVER);
        assertThat(live.data()).containsEntry("incidentId", INCIDENT).containsKey("responder");
        verifyNoMoreInteractions(publisher);

        assertThat(store.slot(DRIVER, "incident")).containsEntry("status", "ACCEPTED").containsKey("responder");
        assertThat(store.slot(RESPONDER, "offer")).isNull();
        assertThat(store.slot(RESPONDER, "incident")).containsEntry("status", "ACCEPTED");
        assertThat(store.links.get(INCIDENT)).containsEntry("driverId", DRIVER)
                .containsEntry("responderId", RESPONDER).containsEntry("status", "ACCEPTED");
    }

    @Test
    @DisplayName("INCIDENT_STATUS goes to the driver AND the responder")
    void status() {
        fanOut.handle(event(incidentJson("INCIDENT_ACCEPTED", RESPONDER,
                "{\"incidentId\":\"" + INCIDENT + "\",\"responder\":{\"name\":\"Bilal\"}}")));
        org.mockito.Mockito.clearInvocations(publisher);

        fanOut.handle(event(incidentJson("INCIDENT_STATUS", RESPONDER,
                "{\"incidentId\":\"" + INCIDENT + "\",\"status\":\"ARRIVED\"}")));

        assertThat(pushedTo(DRIVER).data()).containsEntry("status", "ARRIVED");
        assertThat(pushedTo(RESPONDER).data()).containsEntry("status", "ARRIVED");
        verifyNoMoreInteractions(publisher);
        // the driver's incident keeps the responder and gets the new status
        assertThat(store.slot(DRIVER, "incident")).containsEntry("status", "ARRIVED").containsKey("responder");
        assertThat(store.slot(RESPONDER, "incident")).containsEntry("status", "ARRIVED");
        assertThat(store.links.get(INCIDENT)).containsEntry("status", "ARRIVED");
    }

    @Test
    @DisplayName("INCIDENT_STATUS with no earlier snapshot still builds the incident slot")
    void statusWithoutHistory() {
        fanOut.handle(event(incidentJson("INCIDENT_STATUS", RESPONDER,
                "{\"incidentId\":\"" + INCIDENT + "\",\"status\":\"ARRIVED\"}")));

        assertThat(store.slot(DRIVER, "incident")).containsEntry("incidentId", INCIDENT)
                .containsEntry("status", "ARRIVED");
    }

    @Test
    @DisplayName("RESOLVED clears the incident for both and drops the call link")
    void resolved() {
        fanOut.handle(event(incidentJson("INCIDENT_ACCEPTED", RESPONDER,
                "{\"incidentId\":\"" + INCIDENT + "\",\"responder\":{\"name\":\"Bilal\"}}")));

        fanOut.handle(event(incidentJson("INCIDENT_STATUS", RESPONDER,
                "{\"incidentId\":\"" + INCIDENT + "\",\"status\":\"RESOLVED\"}")));

        assertThat(store.slot(DRIVER, "incident")).isNull();
        assertThat(store.slot(RESPONDER, "incident")).isNull();
        assertThat(store.links).doesNotContainKey(INCIDENT);
        verify(publisher, times(2)).sendEvent(eq(DRIVER), any(LiveEvent.class));   // accepted + resolved
        verify(publisher, times(1)).sendEvent(eq(RESPONDER), any(LiveEvent.class)); // resolved
    }

    @Test
    @DisplayName("INCIDENT_STAND_DOWN goes to the responder only; everything about the incident is cleared")
    void standDown() {
        store.putSnapshot(RESPONDER, "offer", Map.of("incidentId", INCIDENT));
        store.putSnapshot(RESPONDER, "incident", Map.of("incidentId", INCIDENT));
        store.putSnapshot(DRIVER, "incident", Map.of("incidentId", INCIDENT));
        store.putLink(INCIDENT, Map.of("driverId", DRIVER));

        fanOut.handle(event(incidentJson("INCIDENT_STAND_DOWN", RESPONDER,
                "{\"incidentId\":\"" + INCIDENT + "\",\"reason\":\"DRIVER_CANCELLED\"}")));

        LiveEvent live = pushedTo(RESPONDER);
        assertThat(live.data()).containsEntry("reason", "DRIVER_CANCELLED");
        verifyNoMoreInteractions(publisher);
        assertThat(store.slot(RESPONDER, "offer")).isNull();
        assertThat(store.slot(RESPONDER, "incident")).isNull();
        assertThat(store.slot(DRIVER, "incident")).isNull();
        assertThat(store.links).doesNotContainKey(INCIDENT);
    }

    @Test
    @DisplayName("BYSTANDER_ASSISTING goes to the assigned responder only")
    void bystander() {
        fanOut.handle(event(incidentJson("BYSTANDER_ASSISTING", RESPONDER, "{\"incidentId\":\"" + INCIDENT + "\"}")));

        assertThat(pushedTo(RESPONDER).type()).isEqualTo("BYSTANDER_ASSISTING");
        verifyNoMoreInteractions(publisher);
    }

    @Test
    @DisplayName("BYSTANDER_ASSISTING with no responder yet tells nobody")
    void bystanderNoResponder() {
        fanOut.handle(event(incidentJson("BYSTANDER_ASSISTING", null, "{\"incidentId\":\"" + INCIDENT + "\"}")));
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("NO_RESPONDER_AVAILABLE goes to the driver and marks the incident")
    void noResponder() {
        store.putSnapshot(DRIVER, "incident", Map.of("incidentId", INCIDENT, "status", "DISPATCHING"));

        fanOut.handle(event(incidentJson("NO_RESPONDER_AVAILABLE", null, "{\"incidentId\":\"" + INCIDENT + "\"}")));

        assertThat(pushedTo(DRIVER).type()).isEqualTo("NO_RESPONDER_AVAILABLE");
        assertThat(store.slot(DRIVER, "incident")).containsEntry("noResponderAvailable", true)
                .containsEntry("status", "DISPATCHING");
    }

    // ───────────────────────── safety rules ─────────────────────────

    @Test
    @DisplayName("A third user receives nothing from a whole incident")
    void thirdUserGetsNothing() {
        fanOut.handle(event(crashJson("CRASH_PENDING", "\"windowEndsAt\":\"2026-10-29T18:30:20Z\",\"source\":\"HTTP\"")));
        fanOut.handle(event(crashJson("CRASH_DETECTED", "")));
        fanOut.handle(event(incidentJson("INCIDENT_CREATED", null, "{\"incidentId\":\"" + INCIDENT + "\"}")));
        fanOut.handle(event(incidentJson("INCIDENT_OFFERED", RESPONDER, "{\"incidentId\":\"" + INCIDENT + "\"}")));
        fanOut.handle(event(incidentJson("INCIDENT_ACCEPTED", RESPONDER, "{\"incidentId\":\"" + INCIDENT + "\"}")));
        fanOut.handle(event(incidentJson("BYSTANDER_ASSISTING", RESPONDER, "{\"incidentId\":\"" + INCIDENT + "\"}")));
        fanOut.handle(event(incidentJson("INCIDENT_STATUS", RESPONDER, "{\"incidentId\":\"" + INCIDENT + "\",\"status\":\"ARRIVED\"}")));
        fanOut.handle(event(incidentJson("INCIDENT_STATUS", RESPONDER, "{\"incidentId\":\"" + INCIDENT + "\",\"status\":\"RESOLVED\"}")));

        verify(publisher, never()).sendEvent(eq(OTHER), any(LiveEvent.class));
        verify(publisher, never()).sendCall(anyString(), any());
        assertThat(store.snapshots).doesNotContainKey(OTHER);
    }

    @Test
    @DisplayName("An old event updates the snapshot but is not pushed to phones")
    void oldEventNotPushed() {
        // 5 minutes older than "now" (limit is 120 s)
        fanOut.handle(event("{\"eventType\":\"INCIDENT_OFFERED\",\"incidentId\":\"" + INCIDENT + "\","
                + "\"driverId\":\"" + DRIVER + "\",\"responderId\":\"" + RESPONDER + "\","
                + "\"timestamp\":\"2026-10-29T18:25:00Z\",\"data\":{\"incidentId\":\"" + INCIDENT + "\"}}"));

        verifyNoInteractions(publisher);
        assertThat(store.slot(RESPONDER, "offer")).isNotNull();
    }

    @Test
    @DisplayName("An event with no time is treated as fresh")
    void noTimestampIsFresh() {
        fanOut.handle(event("{\"eventType\":\"BYSTANDER_ASSISTING\",\"incidentId\":\"" + INCIDENT
                + "\",\"responderId\":\"" + RESPONDER + "\"}"));
        assertThat(pushedTo(RESPONDER).type()).isEqualTo("BYSTANDER_ASSISTING");
    }

    @Test
    @DisplayName("If Redis is down the live message is still pushed")
    void storeDownStillPushes() {
        store.broken = true;

        fanOut.handle(event(incidentJson("INCIDENT_OFFERED", RESPONDER, "{\"incidentId\":\"" + INCIDENT + "\"}")));

        assertThat(pushedTo(RESPONDER).type()).isEqualTo("INCIDENT_OFFERED");
    }

    @Test
    @DisplayName("If the push fails nothing is thrown (Kafka must keep moving)")
    void pushFailureSwallowed() {
        doThrow(new IllegalStateException("broker gone")).when(publisher).sendEvent(anyString(), any(LiveEvent.class));

        assertThatCode(() -> fanOut.handle(event(crashJson("CRASH_PENDING", "\"source\":\"HTTP\""))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Unknown, missing and null events are ignored")
    void ignored() {
        fanOut.handle(event("{\"eventType\":\"SOMETHING_NEW\",\"driverId\":\"" + DRIVER + "\"}"));
        fanOut.handle(event("{\"driverId\":\"" + DRIVER + "\"}"));
        fanOut.handle(null);
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("The same event for the same person always has the same eventId; another person gets another")
    void eventIdStable() {
        KafkaEvent e = event(incidentJson("INCIDENT_STATUS", RESPONDER, "{\"status\":\"ARRIVED\"}"));
        String first = FanOutService.eventId(e, DRIVER);
        assertThat(first).isEqualTo(FanOutService.eventId(e, DRIVER));
        assertThat(first).isNotEqualTo(FanOutService.eventId(e, RESPONDER));
        assertThat(first).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("Two pushes of one STATUS event carry two different eventIds (driver and responder)")
    void statusEventIdsDiffer() {
        fanOut.handle(event(incidentJson("INCIDENT_STATUS", RESPONDER,
                "{\"incidentId\":\"" + INCIDENT + "\",\"status\":\"ARRIVED\"}")));

        ArgumentCaptor<LiveEvent> captor = ArgumentCaptor.forClass(LiveEvent.class);
        verify(publisher, times(2)).sendEvent(anyString(), captor.capture());
        List<LiveEvent> sent = captor.getAllValues();
        assertThat(sent.get(0).eventId()).isNotEqualTo(sent.get(1).eventId());
    }
}
