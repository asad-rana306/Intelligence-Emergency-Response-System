package com.iers.realtime.store;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** A store that lives in memory, for tests. Set "broken" to act like Redis is down. */
public class InMemoryLiveStateStore implements LiveStateStore {

    public final Map<String, Map<String, Map<String, Object>>> snapshots = new HashMap<>();
    public final Map<String, Map<String, String>> links = new HashMap<>();
    public boolean broken = false;

    private void check() {
        if (broken) {
            throw new IllegalStateException("redis is down");
        }
    }

    @Override
    public void putSnapshot(String userId, String slot, Map<String, Object> value) {
        check();
        snapshots.computeIfAbsent(userId, k -> new LinkedHashMap<>()).put(slot, new LinkedHashMap<>(value));
    }

    @Override
    public void removeSnapshot(String userId, String slot) {
        check();
        Map<String, Map<String, Object>> mine = snapshots.get(userId);
        if (mine != null) {
            mine.remove(slot);
        }
    }

    @Override
    public Map<String, Map<String, Object>> loadSnapshot(String userId) {
        check();
        return new LinkedHashMap<>(snapshots.getOrDefault(userId, Map.of()));
    }

    @Override
    public void putLink(String incidentId, Map<String, String> fields) {
        check();
        links.computeIfAbsent(incidentId, k -> new LinkedHashMap<>()).putAll(fields);
    }

    @Override
    public Map<String, String> getLink(String incidentId) {
        check();
        return new LinkedHashMap<>(links.getOrDefault(incidentId, Map.of()));
    }

    @Override
    public void removeLink(String incidentId) {
        check();
        links.remove(incidentId);
    }

    /** One slot of one user, or null. */
    public Map<String, Object> slot(String userId, String slot) {
        return snapshots.getOrDefault(userId, Map.of()).get(slot);
    }
}
