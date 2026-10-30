package com.iers.realtime.websocket;

import java.security.Principal;
import java.util.Objects;

/** The logged-in person of a WebSocket connection. The name is the user ID the gateway passed on. */
public final class StompUser implements Principal {

    private final String name;

    public StompUser(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StompUser other && Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "StompUser[" + name + "]";
    }
}
