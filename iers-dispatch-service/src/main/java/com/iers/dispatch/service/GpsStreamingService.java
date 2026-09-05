package com.iers.dispatch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.dispatch.dto.request.GpsUpdateMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Handles live GPS streaming during an active rescue.
 *
 * Data path: WebSocket → Redis SET → STOMP broadcast
 * NO Postgres involved — per the performance constraint.
 *
 * - Latest position stored in Redis: gps:latest:{incidentId} (overwritten each update)
 * - All subscribers receive the update via STOMP: /topic/gps/{incidentId}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GpsStreamingService {

    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    private static final Duration GPS_KEY_TTL = Duration.ofMinutes(30);

    /**
     * Process an incoming GPS update from a responder.
     * Store in Redis and broadcast to all WebSocket subscribers.
     */
    public void processGpsUpdate(GpsUpdateMessage message) {
        String incidentId = message.getIncidentId();

        // ── Store latest position in Redis ──
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.opsForValue().set("gps:latest:" + incidentId, json, GPS_KEY_TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize GPS update for incident={}: {}", incidentId, e.getMessage());
        }

        // ── Broadcast to STOMP subscribers ──
        messagingTemplate.convertAndSend("/topic/gps/" + incidentId, message);

        log.debug("GPS update: incident={}, responder={}, pos=[{},{}]",
                incidentId, message.getResponderId(),
                message.getLatitude(), message.getLongitude());
    }

    /**
     * Get the latest known position for a reconnecting client.
     */
    public String getLatestPosition(String incidentId) {
        return redisTemplate.opsForValue().get("gps:latest:" + incidentId);
    }

    /**
     * Clean up GPS data when an incident is resolved.
     */
    public void cleanupIncident(String incidentId) {
        redisTemplate.delete("gps:latest:" + incidentId);
        log.info("GPS data cleaned up for incident={}", incidentId);
    }
}
