package com.iers.realtime.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisLiveStateStoreTest {

    private StringRedisTemplate redis;
    private HashOperations<String, String, String> ops;
    private RedisLiveStateStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(HashOperations.class);
        doReturn(ops).when(redis).opsForHash();
        store = new RedisLiveStateStore(redis, new ObjectMapper(), Duration.ofMinutes(120));
    }

    @Test
    @DisplayName("A snapshot slot is stored as JSON in the user's hash, and the expiry is renewed")
    void putSnapshot() {
        store.putSnapshot("u1", "offer", Map.of("incidentId", "i1"));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(ops).put(org.mockito.ArgumentMatchers.eq("live:snap:u1"),
                org.mockito.ArgumentMatchers.eq("offer"), json.capture());
        assertThat(json.getValue()).isEqualTo("{\"incidentId\":\"i1\"}");
        verify(redis).expire("live:snap:u1", Duration.ofMinutes(120));
    }

    @Test
    @DisplayName("Removing a slot deletes that field only")
    void removeSnapshot() {
        store.removeSnapshot("u1", "crash");
        verify(ops).delete("live:snap:u1", "crash");
    }

    @Test
    @DisplayName("Loading reads every slot back into maps and skips unreadable ones")
    void loadSnapshot() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("crash", "{\"crashEventId\":\"c1\"}");
        raw.put("offer", "{{ broken");
        when(ops.entries("live:snap:u1")).thenReturn(raw);

        Map<String, Map<String, Object>> loaded = store.loadSnapshot("u1");

        assertThat(loaded).containsOnlyKeys("crash");
        assertThat(loaded.get("crash")).containsEntry("crashEventId", "c1");
    }

    @Test
    @DisplayName("Loading a user with nothing gives an empty map (never null)")
    void loadEmpty() {
        when(ops.entries("live:snap:nobody")).thenReturn(Map.of());
        assertThat(store.loadSnapshot("nobody")).isEmpty();
    }

    @Test
    @DisplayName("Link fields are written together and the expiry is renewed")
    void putLink() {
        store.putLink("i1", Map.of("driverId", "d1"));
        verify(ops).putAll("live:inc:i1", Map.of("driverId", "d1"));
        verify(redis).expire("live:inc:i1", Duration.ofMinutes(120));
    }

    @Test
    @DisplayName("An empty link write does nothing")
    void putLinkEmpty() {
        store.putLink("i1", Map.of());
        store.putLink("i1", null);
        verify(ops, never()).putAll(anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("Reading a link gives its fields; a missing link gives an empty map")
    void getLink() {
        when(ops.entries("live:inc:i1")).thenReturn(Map.of("driverId", "d1", "status", "ACCEPTED"));
        when(ops.entries("live:inc:none")).thenReturn(Map.of());

        assertThat(store.getLink("i1")).containsEntry("driverId", "d1").containsEntry("status", "ACCEPTED");
        assertThat(store.getLink("none")).isEmpty();
    }

    @Test
    @DisplayName("Removing a link deletes its key")
    void removeLink() {
        store.removeLink("i1");
        verify(redis).delete("live:inc:i1");
    }
}
