package com.iers.realtime.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** The queues are private because of these door rules. */
class StompGuardInterceptorTest {

    private static final String INCIDENT = "7c1d9e22-0000-4000-8000-0000000000aa";

    private final StompGuardInterceptor guard = new StompGuardInterceptor();
    private final MessageChannel channel = mock(MessageChannel.class);

    private Message<byte[]> frame(StompCommand command, String destination, Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setUser(user);
        accessor.setSessionId("session-1");
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Object send(Message<byte[]> frame) {
        return guard.preSend(frame, channel);
    }

    @Test
    @DisplayName("CONNECT with a user is accepted; without a user it is refused")
    void connect() {
        Message<byte[]> ok = frame(StompCommand.CONNECT, null, new StompUser("u1"));
        assertThat(send(ok)).isSameAs(ok);

        assertThatThrownBy(() -> send(frame(StompCommand.CONNECT, null, null)))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> send(frame(StompCommand.STOMP, null, null)))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("SUBSCRIBE: only the two private queues are allowed")
    void subscribeAllowed() {
        Message<byte[]> events = frame(StompCommand.SUBSCRIBE, "/user/queue/events", new StompUser("u1"));
        Message<byte[]> call = frame(StompCommand.SUBSCRIBE, "/user/queue/call", new StompUser("u1"));
        assertThat(send(events)).isSameAs(events);
        assertThat(send(call)).isSameAs(call);
    }

    @Test
    @DisplayName("SUBSCRIBE to anything else is refused: somebody else's queue, topics, or no destination")
    void subscribeRefused() {
        for (String destination : new String[]{
                "/queue/events-userABC123",       // the real broker name of a queue
                "/queue/events",
                "/user/someone-else/queue/events",
                "/topic/gps/" + INCIDENT,
                "/user/queue/other"}) {
            assertThatThrownBy(() -> send(frame(StompCommand.SUBSCRIBE, destination, new StompUser("u1"))))
                    .as(destination)
                    .isInstanceOf(MessageDeliveryException.class);
        }
        assertThatThrownBy(() -> send(frame(StompCommand.SUBSCRIBE, null, new StompUser("u1"))))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("SEND: only /app/call/{uuid} and /app/snapshot are allowed")
    void sendAllowed() {
        Message<byte[]> call = frame(StompCommand.SEND, "/app/call/" + INCIDENT, new StompUser("u1"));
        Message<byte[]> upper = frame(StompCommand.SEND, "/app/call/" + INCIDENT.toUpperCase(), new StompUser("u1"));
        Message<byte[]> snapshot = frame(StompCommand.SEND, "/app/snapshot", new StompUser("u1"));
        assertThat(send(call)).isSameAs(call);
        assertThat(send(upper)).isSameAs(upper);
        assertThat(send(snapshot)).isSameAs(snapshot);
    }

    @Test
    @DisplayName("SEND to anything else is refused: bad id, other paths, the GPS door, no destination")
    void sendRefused() {
        for (String destination : new String[]{
                "/app/call/1",
                "/app/call/" + INCIDENT + "/extra",
                "/app/gps/" + INCIDENT,
                "/app/other",
                "/queue/call",
                "/user/queue/events"}) {
            assertThatThrownBy(() -> send(frame(StompCommand.SEND, destination, new StompUser("u1"))))
                    .as(destination)
                    .isInstanceOf(MessageDeliveryException.class);
        }
        assertThatThrownBy(() -> send(frame(StompCommand.SEND, null, new StompUser("u1"))))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("UNSUBSCRIBE, DISCONNECT and heartbeats pass through")
    void others() {
        Message<byte[]> unsubscribe = frame(StompCommand.UNSUBSCRIBE, null, new StompUser("u1"));
        Message<byte[]> disconnect = frame(StompCommand.DISCONNECT, null, new StompUser("u1"));
        assertThat(send(unsubscribe)).isSameAs(unsubscribe);
        assertThat(send(disconnect)).isSameAs(disconnect);

        StompHeaderAccessor heartbeat = StompHeaderAccessor.createForHeartbeat();
        Message<byte[]> beat = MessageBuilder.createMessage(new byte[0], heartbeat.getMessageHeaders());
        assertThat(send(beat)).isSameAs(beat);
    }
}
