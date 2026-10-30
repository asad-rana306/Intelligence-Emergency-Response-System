package com.iers.realtime.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The door rules for every STOMP frame a phone sends. This is what makes the queues private:
 *
 *  - CONNECT   only with a known user (set by GatewayIdentityFilter).
 *  - SUBSCRIBE only to /user/queue/events and /user/queue/call. The broker turns those into
 *              the caller's own queue. Any other destination (for example somebody else's
 *              queue name) is refused.
 *  - SEND      only to /app/call/{incidentId} and /app/snapshot.
 *
 * A refused frame makes the server answer with a STOMP ERROR frame.
 */
@Component
public class StompGuardInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompGuardInterceptor.class);

    static final Set<String> ALLOWED_SUBSCRIPTIONS = Set.of("/user/queue/events", "/user/queue/call");
    static final String SNAPSHOT_DESTINATION = "/app/snapshot";
    static final Pattern CALL_DESTINATION = Pattern.compile(
            "^/app/call/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            // Never skip the checks just because the accessor object is missing: read the headers directly.
            accessor = StompHeaderAccessor.wrap(message);
        }
        if (accessor.getCommand() == null) {
            return message;   // a heartbeat, not a command
        }
        StompCommand command = accessor.getCommand();
        String destination = accessor.getDestination();

        switch (command) {
            case CONNECT, STOMP -> {
                if (accessor.getUser() == null) {
                    throw refuse(message, "Not logged in");
                }
            }
            case SUBSCRIBE -> {
                if (destination == null || !ALLOWED_SUBSCRIPTIONS.contains(destination)) {
                    log.warn("Refused SUBSCRIBE to {} by user {}", destination, userOf(accessor));
                    throw refuse(message, "You can only subscribe to /user/queue/events and /user/queue/call");
                }
            }
            case SEND -> {
                boolean allowed = destination != null
                        && (SNAPSHOT_DESTINATION.equals(destination) || CALL_DESTINATION.matcher(destination).matches());
                if (!allowed) {
                    log.warn("Refused SEND to {} by user {}", destination, userOf(accessor));
                    throw refuse(message, "You can only send to /app/call/{incidentId} and /app/snapshot");
                }
            }
            default -> { /* heartbeats, UNSUBSCRIBE, DISCONNECT, ACK: nothing to check */ }
        }
        return message;
    }

    private static MessageDeliveryException refuse(Message<?> message, String reason) {
        return new MessageDeliveryException(message, reason);
    }

    private static String userOf(StompHeaderAccessor accessor) {
        return accessor.getUser() != null ? accessor.getUser().getName() : "unknown";
    }
}
