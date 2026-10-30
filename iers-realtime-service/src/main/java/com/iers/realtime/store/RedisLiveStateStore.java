package com.iers.realtime.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis version of the store. One hash per user ("live:snap:{userId}", one field per slot,
 * value = JSON) and one hash per incident ("live:inc:{incidentId}"). Every write renews the expiry,
 * so old notes clean themselves up.
 */
public class RedisLiveStateStore implements LiveStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisLiveStateStore.class);
    static final String SNAPSHOT_PREFIX = "live:snap:";
    static final String LINK_PREFIX = "live:inc:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public RedisLiveStateStore(StringRedisTemplate redis, ObjectMapper mapper, Duration ttl) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = ttl;
    }

    @Override
    public void putSnapshot(String userId, String slot, Map<String, Object> value) {
        String key = SNAPSHOT_PREFIX + userId;
        HashOperations<String, String, String> ops = redis.opsForHash();
        try {
            ops.put(key, slot, mapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write snapshot slot " + slot, e);
        }
        redis.expire(key, ttl);
    }

    @Override
    public void removeSnapshot(String userId, String slot) {
        HashOperations<String, String, String> ops = redis.opsForHash();
        ops.delete(SNAPSHOT_PREFIX + userId, slot);
    }

    @Override
    public Map<String, Map<String, Object>> loadSnapshot(String userId) {
        HashOperations<String, String, String> ops = redis.opsForHash();
        Map<String, String> raw = ops.entries(SNAPSHOT_PREFIX + userId);
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (raw == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            try {
                Map<String, Object> value = mapper.readValue(entry.getValue(),
                        new TypeReference<LinkedHashMap<String, Object>>() { });
                result.put(entry.getKey(), value);
            } catch (JsonProcessingException e) {
                log.warn("Skipping unreadable snapshot slot {} of user {}", entry.getKey(), userId);
            }
        }
        return result;
    }

    @Override
    public void putLink(String incidentId, Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        String key = LINK_PREFIX + incidentId;
        HashOperations<String, String, String> ops = redis.opsForHash();
        ops.putAll(key, fields);
        redis.expire(key, ttl);
    }

    @Override
    public Map<String, String> getLink(String incidentId) {
        HashOperations<String, String, String> ops = redis.opsForHash();
        Map<String, String> raw = ops.entries(LINK_PREFIX + incidentId);
        return raw == null ? new LinkedHashMap<>() : new LinkedHashMap<>(raw);
    }

    @Override
    public void removeLink(String incidentId) {
        redis.delete(LINK_PREFIX + incidentId);
    }
}
