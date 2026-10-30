package com.iers.realtime.websocket;

import com.iers.realtime.dto.CallMessage;
import com.iers.realtime.service.CallRelayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/** A phone sends a call message to /app/call/{incidentId}. The other person receives it. */
@Controller
public class CallController {

    private static final Logger log = LoggerFactory.getLogger(CallController.class);

    private final CallRelayService relay;

    public CallController(CallRelayService relay) {
        this.relay = relay;
    }

    @MessageMapping("/call/{incidentId}")
    public void call(@DestinationVariable String incidentId, @Payload CallMessage message, Principal principal) {
        relay.relay(principal.getName(), incidentId, message);
    }

    /** A message that is not valid JSON (or not a call message) gets a CALL_ERROR, not silence. */
    @MessageExceptionHandler
    public void unreadable(Exception e, Principal principal) {
        log.info("Unreadable call message from {}: {}", principal != null ? principal.getName() : "unknown", e.getMessage());
        if (principal != null) {
            relay.rejectUnreadable(principal.getName());
        }
    }
}
