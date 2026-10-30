package com.iers.dispatch.dto;

import com.iers.dispatch.entity.Incident;

import java.util.UUID;

/**
 * The logged-in person making a request. The gateway puts these two values in the
 * X-User-Id and X-User-Role headers after it checks the JWT.
 */
public record Viewer(UUID id, String role) {

    public static final String DRIVER = "DRIVER";
    public static final String RESPONDER = "RESPONDER";
    public static final String ADMIN = "ADMIN";

    public boolean isAdmin() {
        return ADMIN.equalsIgnoreCase(role);
    }

    public boolean isDriver() {
        return DRIVER.equalsIgnoreCase(role);
    }

    public boolean isResponder() {
        return RESPONDER.equalsIgnoreCase(role);
    }

    /** This person is the driver who crashed. */
    public boolean isDriverOf(Incident incident) {
        return id != null && id.equals(incident.getDriverId());
    }

    /** This person is the offered responder (DISPATCHED) or the assigned responder (later states). */
    public boolean isResponderOf(Incident incident) {
        return id != null && id.equals(incident.getAssignedResponderId());
    }

    /** Who may open this incident at all: its driver, its responder, or an admin. */
    public boolean canSee(Incident incident) {
        return isAdmin() || isDriverOf(incident) || isResponderOf(incident);
    }
}
