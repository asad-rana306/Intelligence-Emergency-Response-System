package com.iers.iot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.iot.dto.request.HeartbeatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Stores device heartbeats in Redis (NOT Postgres).
 * Heartbeats are ephemeral — TTL 60 seconds, overwritten each ping.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeartbeatService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final Duration HEARTBEAT_TTL = Duration.ofSeconds(60);

    public void storeHeartbeat(String deviceId, HeartbeatRequest request) {
        try {
            String key = "device:heartbeat:" + deviceId;
            String value = objectMapper.writeValueAsString(request);
            redisTemplate.opsForValue().set(key, value, HEARTBEAT_TTL);
            log.debug("Heartbeat stored for device={}, speed={}, gps=[{},{}]",
                    deviceId, request.getSpeed(), request.getGpsLat(), request.getGpsLng());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize heartbeat for device={}: {}", deviceId, e.getMessage());
        }
    }

    public String getLatestHeartbeat(String deviceId) {
        return redisTemplate.opsForValue().get("device:heartbeat:" + deviceId);
    }
}
