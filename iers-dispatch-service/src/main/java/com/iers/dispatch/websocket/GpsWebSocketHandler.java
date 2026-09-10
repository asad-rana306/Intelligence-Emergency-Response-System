package com.iers.dispatch.websocket;

import com.iers.dispatch.dto.request.GpsUpdateMessage;
import com.iers.dispatch.service.GpsStreamingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

/**
 * STOMP message handler for live GPS streaming.
 *
 * Responder app sends:     STOMP SEND /app/gps/{incidentId}
 * Victim/Dashboard receives: STOMP SUBSCRIBE /topic/gps/{incidentId}
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class GpsWebSocketHandler {

    private final GpsStreamingService gpsStreamingService;

    @MessageMapping("/gps/{incidentId}")
    public void handleGpsUpdate(@DestinationVariable String incidentId,
                                 GpsUpdateMessage message) {
        message.setIncidentId(incidentId);
        gpsStreamingService.processGpsUpdate(message);
    }
}
