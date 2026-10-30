package com.iers.dispatch.dto;

import com.iers.dispatch.dto.response.IncidentResponse;
import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.entity.enums.IncidentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Who may see which field (the privacy rules from the API contract). */
class IncidentResponseTest {

    private final UUID driverId = UUID.randomUUID();
    private final UUID responderId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-29T18:30:40Z");

    private Incident incident(IncidentStatus status) {
        return Incident.builder()
                .id(UUID.randomUUID()).crashEventId(UUID.randomUUID()).driverId(driverId)
                .driverName("Ali").driverPhone("+923001234567").priorityScore(4)
                .gpsLat(31.5).gpsLng(74.3).status(status)
                .assignedResponderId(responderId)
                .responderName("Bilal").responderPhone("+923009876543").responderVehicleId("AMB-12")
                .bloodType("O+").allergies("Penicillin")
                .dispatchExpiresAt(now.plusSeconds(20))
                .createdAt(now.minusSeconds(30)).build();
    }

    @Test
    @DisplayName("Offered responder: sees medical info but NOT the driver's phone, and sees the countdown")
    void offeredResponder() {
        IncidentResponse r = IncidentResponse.from(
                incident(IncidentStatus.DISPATCHED), new Viewer(responderId, "RESPONDER"), now);

        assertThat(r.getBloodType()).isEqualTo("O+");
        assertThat(r.getAllergies()).isEqualTo("Penicillin");
        assertThat(r.getDriverPhone()).isNull();
        assertThat(r.getResponder()).isNull();
        assertThat(r.getDispatchExpiresAt()).isEqualTo(now.plusSeconds(20));
        assertThat(r.getServerTime()).isEqualTo(now);
    }

    @Test
    @DisplayName("Assigned responder after accepting: also gets the driver's phone")
    void assignedResponder() {
        IncidentResponse r = IncidentResponse.from(
                incident(IncidentStatus.ACCEPTED), new Viewer(responderId, "RESPONDER"), now);

        assertThat(r.getDriverPhone()).isEqualTo("+923001234567");
        assertThat(r.getBloodType()).isEqualTo("O+");
        assertThat(r.getDispatchExpiresAt()).isNull(); // only while DISPATCHED
    }

    @Test
    @DisplayName("Driver: sees medical info and the responder, but not the driver phone field")
    void driver() {
        IncidentResponse r = IncidentResponse.from(
                incident(IncidentStatus.ACCEPTED), new Viewer(driverId, "DRIVER"), now);

        assertThat(r.getBloodType()).isEqualTo("O+");
        assertThat(r.getDriverPhone()).isNull();
        assertThat(r.getDriverId()).isEqualTo(driverId.toString());
        assertThat(r.getResponder()).isNotNull();
        assertThat(r.getResponder().getName()).isEqualTo("Bilal");
        assertThat(r.getResponder().getPhone()).isEqualTo("+923009876543");
        assertThat(r.getResponder().getVehicleId()).isEqualTo("AMB-12");
    }

    @Test
    @DisplayName("Responder is null until the incident is accepted")
    void responderHiddenBeforeAccept() {
        IncidentResponse r = IncidentResponse.from(
                incident(IncidentStatus.DISPATCHED), new Viewer(driverId, "DRIVER"), now);
        assertThat(r.getResponder()).isNull();
    }

    @Test
    @DisplayName("Admin can open the incident, but gets no driver phone and no medical info (contract)")
    void admin() {
        IncidentResponse r = IncidentResponse.from(
                incident(IncidentStatus.ARRIVED), new Viewer(UUID.randomUUID(), "ADMIN"), now);

        assertThat(r.getId()).isNotNull();
        assertThat(r.getDriverPhone()).isNull();
        assertThat(r.getBloodType()).isNull();
        assertThat(r.getAllergies()).isNull();
        assertThat(r.getResponder()).isNotNull();
    }

    @Test
    @DisplayName("A stranger sees no private fields")
    void stranger() {
        IncidentResponse r = IncidentResponse.from(
                incident(IncidentStatus.ACCEPTED), new Viewer(UUID.randomUUID(), "DRIVER"), now);

        assertThat(r.getDriverPhone()).isNull();
        assertThat(r.getBloodType()).isNull();
        assertThat(r.getAllergies()).isNull();
    }

    @Test
    @DisplayName("An incident with no driver or responder yet maps cleanly")
    void emptyIds() {
        Incident inc = incident(IncidentStatus.DISPATCHING);
        inc.setDriverId(null);
        inc.setAssignedResponderId(null);

        IncidentResponse r = IncidentResponse.from(inc, new Viewer(UUID.randomUUID(), "ADMIN"), now);

        assertThat(r.getDriverId()).isNull();
        assertThat(r.getAssignedResponderId()).isNull();
        assertThat(r.getResponder()).isNull();
    }

    @Test
    @DisplayName("Viewer role checks ignore case and handle a missing id")
    void viewerRoles() {
        assertThat(new Viewer(driverId, "driver").isDriver()).isTrue();
        assertThat(new Viewer(driverId, "Responder").isResponder()).isTrue();
        assertThat(new Viewer(driverId, "admin").isAdmin()).isTrue();
        assertThat(new Viewer(driverId, "UNKNOWN").isAdmin()).isFalse();
        assertThat(new Viewer(null, "DRIVER").isDriverOf(incident(IncidentStatus.ACCEPTED))).isFalse();
        assertThat(new Viewer(null, "RESPONDER").isResponderOf(incident(IncidentStatus.ACCEPTED))).isFalse();
    }
}
