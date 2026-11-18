package com.iers.dispatch.push;

import com.iers.dispatch.dto.feign.InvalidateTokensRequest;
import com.iers.dispatch.feign.AuthServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushServiceTest {

    @Mock private AuthServiceClient authClient;
    @Mock private PushSender sender;

    private PushService service;
    private final UUID userId = UUID.randomUUID();
    private final PushMessage message = PushMessage.standDown(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        service = new PushService(authClient, sender);
    }

    @Test
    @DisplayName("the push goes to every phone of the user")
    void allPhones() {
        when(authClient.getPushTokens(userId)).thenReturn(List.of("t1", "t2"));
        when(sender.send(anyString(), eq(message))).thenReturn(PushOutcome.SENT);

        assertThat(service.sendToUser(userId, message)).isEqualTo(2);

        verify(sender).send("t1", message);
        verify(sender).send("t2", message);
        verify(authClient, never()).invalidatePushTokens(any());
    }

    @Test
    @DisplayName("a token FCM calls dead is reported to Auth, the others still get the push")
    void deadTokenReported() {
        when(authClient.getPushTokens(userId)).thenReturn(List.of("good", "dead"));
        when(sender.send("good", message)).thenReturn(PushOutcome.SENT);
        when(sender.send("dead", message)).thenReturn(PushOutcome.DEAD_TOKEN);

        assertThat(service.sendToUser(userId, message)).isEqualTo(1);

        verify(authClient).invalidatePushTokens(new InvalidateTokensRequest(List.of("dead")));
    }

    @Test
    @DisplayName("a token that only failed (FCM hiccup) is kept")
    void failedTokenIsKept() {
        when(authClient.getPushTokens(userId)).thenReturn(List.of("t1"));
        when(sender.send("t1", message)).thenReturn(PushOutcome.FAILED);

        assertThat(service.sendToUser(userId, message)).isZero();

        verify(authClient, never()).invalidatePushTokens(any());
    }

    @Test
    @DisplayName("no token: nothing is sent")
    void noTokens() {
        when(authClient.getPushTokens(userId)).thenReturn(List.of());

        assertThat(service.sendToUser(userId, message)).isZero();

        verifyNoInteractions(sender);
    }

    @Test
    @DisplayName("Auth cannot give the tokens: no exception, nothing sent")
    void authFails() {
        when(authClient.getPushTokens(userId)).thenThrow(new RuntimeException("auth down"));

        assertThat(service.sendToUser(userId, message)).isZero();

        verifyNoInteractions(sender);
    }

    @Test
    @DisplayName("Auth cannot take the dead-token report: no exception")
    void invalidateFails() {
        when(authClient.getPushTokens(userId)).thenReturn(List.of("dead"));
        when(sender.send("dead", message)).thenReturn(PushOutcome.DEAD_TOKEN);
        doThrow(new RuntimeException("auth down")).when(authClient).invalidatePushTokens(any());

        assertThat(service.sendToUser(userId, message)).isZero();
    }
}
