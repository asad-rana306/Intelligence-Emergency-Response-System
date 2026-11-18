package com.iers.dispatch.push;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FcmPushSenderTest {

    private FirebaseMessaging fcm;
    private FcmPushSender sender;
    private final PushMessage offer = PushMessage.offer(UUID.randomUUID(), 1.0, 2.0, "Ali", Duration.ofSeconds(30));

    @BeforeEach
    void setUp() {
        fcm = mock(FirebaseMessaging.class);
        sender = new FcmPushSender(fcm);
    }

    private FirebaseMessagingException failure(MessagingErrorCode code) {
        FirebaseMessagingException e = mock(FirebaseMessagingException.class);
        when(e.getMessagingErrorCode()).thenReturn(code);
        return e;
    }

    @Test
    @DisplayName("accepted by FCM = SENT")
    void sent() throws Exception {
        when(fcm.send(any(Message.class))).thenReturn("projects/p/messages/1");
        assertThat(sender.send("token-123456", offer)).isEqualTo(PushOutcome.SENT);
    }

    @Test
    @DisplayName("an urgent message with a lifetime, and a data-only message without one, both build and send")
    void bothMessageKinds() throws Exception {
        when(fcm.send(any(Message.class))).thenReturn("id");
        assertThat(sender.send("token-123456", offer)).isEqualTo(PushOutcome.SENT);
        assertThat(sender.send("token-123456", PushMessage.offDuty("MAX_DURATION"))).isEqualTo(PushOutcome.SENT);
    }

    @Test
    @DisplayName("UNREGISTERED and SENDER_ID_MISMATCH mean the token is dead")
    void deadTokens() throws Exception {
        when(fcm.send(any(Message.class)))
                .thenThrow(failure(MessagingErrorCode.UNREGISTERED))
                .thenThrow(failure(MessagingErrorCode.SENDER_ID_MISMATCH));

        assertThat(sender.send("token-123456", offer)).isEqualTo(PushOutcome.DEAD_TOKEN);
        assertThat(sender.send("token-123456", offer)).isEqualTo(PushOutcome.DEAD_TOKEN);
    }

    @Test
    @DisplayName("other FCM errors keep the token (a bad payload must not wipe every token)")
    void otherErrorsKeepToken() throws Exception {
        when(fcm.send(any(Message.class)))
                .thenThrow(failure(MessagingErrorCode.INTERNAL))
                .thenThrow(failure(MessagingErrorCode.INVALID_ARGUMENT))
                .thenThrow(new IllegalStateException("boom"));

        assertThat(sender.send("token-123456", offer)).isEqualTo(PushOutcome.FAILED);
        assertThat(sender.send("token-123456", offer)).isEqualTo(PushOutcome.FAILED);
        assertThat(sender.send("token-123456", offer)).isEqualTo(PushOutcome.FAILED);
    }
}
