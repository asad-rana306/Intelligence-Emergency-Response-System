package com.iers.iot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iers.iot.dto.request.HeartbeatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HeartbeatServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private HeartbeatService service;

    @Test
    @DisplayName("storeHeartbeat — writes to Redis with 60s TTL")
    void storeHeartbeat_success() {
        HeartbeatRequest request = HeartbeatRequest.builder()
                .speed(65.0).gpsLat(37.77).gpsLng(-122.41)
                .hardwareHealth("OK").timestamp(System.currentTimeMillis()).build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.storeHeartbeat("DEV-001", request);

        verify(valueOperations).set(
                eq("device:heartbeat:DEV-001"),
                contains("65.0"),
                eq(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("getLatestHeartbeat — retrieves from Redis")
    void getLatestHeartbeat() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("device:heartbeat:DEV-001")).thenReturn("{\"speed\":65.0}");

        String result = service.getLatestHeartbeat("DEV-001");

        assertThat(result).contains("65.0");
    }

    @Test
    @DisplayName("getLatestHeartbeat — returns null for unknown device")
    void getLatestHeartbeat_unknown() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("device:heartbeat:UNKNOWN")).thenReturn(null);

        String result = service.getLatestHeartbeat("UNKNOWN");

        assertThat(result).isNull();
    }
}
