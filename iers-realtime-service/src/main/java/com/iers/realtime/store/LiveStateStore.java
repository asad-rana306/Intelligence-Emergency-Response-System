package com.iers.realtime.store;

import java.util.Map;

/**
 * Small memory the Realtime service keeps so a phone that reconnects can catch up,
 * and so call messages know who the two people of an incident are.
 *
 * Two kinds of notes:
 *  - snapshot: per user, three slots ("crash", "offer", "incident"), each a small map
 *  - link: per incident, the two people and the status: driverId, responderId, status
 *
 * Implementations may throw if the storage is down. The callers catch that: a broken
 * memory must never stop a live message.
 */
public interface LiveStateStore {

    String CRASH = "crash";
    String OFFER = "offer";
    String INCIDENT = "incident";

    void putSnapshot(String userId, String slot, Map<String, Object> value);

    void removeSnapshot(String userId, String slot);

    /** slot -> value. Slots with nothing in them are missing from the map. Never null. */
    Map<String, Map<String, Object>> loadSnapshot(String userId);

    /** Adds or overwrites these fields (other fields of the link stay). */
    void putLink(String incidentId, Map<String, String> fields);

    /** Empty map when there is no link. Never null. */
    Map<String, String> getLink(String incidentId);

    void removeLink(String incidentId);
}
