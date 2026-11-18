package com.iers.dispatch.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The "type" and the keys here are what the Flutter app reads. Do not change them casually. */
class PushMessageTest {

    private final UUID incidentId = UUID.randomUUID();

    @Test
    @DisplayName("OFFER: type, incidentId, lat, lng, driverName; urgent; lifetime = offer time")
    void offer() {
        PushMessage m = PushMessage.offer(incidentId, 31.5, 74.3, "Ali", Duration.ofSeconds(30));

        assertThat(m.data()).containsEntry("type", "OFFER")
                .containsEntry("incidentId", incidentId.toString())
                .containsEntry("lat", "31.5").containsEntry("lng", "74.3")
                .containsEntry("driverName", "Ali");
        assertThat(m.urgent()).isTrue();
        assertThat(m.ttl()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("OFFER with missing values: empty text, never null (FCM rejects null)")
    void offerWithMissingValues() {
        PushMessage m = PushMessage.offer(incidentId, null, null, null, null);

        assertThat(m.data()).containsEntry("lat", "").containsEntry("lng", "").containsEntry("driverName", "");
        assertThat(m.ttl()).isNull();
    }

    @Test
    @DisplayName("ACCEPTED, STAND_DOWN")
    void acceptedAndStandDown() {
        PushMessage accepted = PushMessage.accepted(incidentId, "Bilal");
        assertThat(accepted.data()).containsEntry("type", "ACCEPTED").containsEntry("responderName", "Bilal");
        assertThat(accepted.body()).contains("Bilal");
        assertThat(PushMessage.accepted(incidentId, null).data()).containsEntry("responderName", "");

        PushMessage stand = PushMessage.standDown(incidentId);
        assertThat(stand.data()).containsEntry("type", "STAND_DOWN").containsEntry("incidentId", incidentId.toString());
        assertThat(stand.urgent()).isTrue();
    }

    @Test
    @DisplayName("OFF_DUTY: the text depends on the reason, it is not urgent")
    void offDuty() {
        PushMessage shift = PushMessage.offDuty("MAX_DURATION");
        assertThat(shift.data()).containsEntry("type", "OFF_DUTY").containsEntry("reason", "MAX_DURATION");
        assertThat(shift.body()).contains("1-hour shift");
        assertThat(shift.urgent()).isFalse();
        assertThat(shift.dataOnly()).isTrue();
        assertThat(shift.data()).containsEntry("title", "You are now OFF duty").containsKey("body");

        assertThat(PushMessage.offDuty("HEARTBEAT_LOST").body()).contains("lost contact");
        assertThat(PushMessage.offDuty(null).data()).containsEntry("reason", "");
    }

    @Test
    @DisplayName("no message ever carries a null value in its data")
    void noNullValues() {
        List<PushMessage> all = List.of(
                PushMessage.offer(incidentId, null, null, null, null),
                PushMessage.accepted(incidentId, null),
                PushMessage.standDown(incidentId),
                PushMessage.offDuty(null));
        for (PushMessage m : all) {
            assertThat(m.data().values()).doesNotContainNull();
            assertThat(m.title()).isNotBlank();
            assertThat(m.body()).isNotBlank();
        }
    }
}
