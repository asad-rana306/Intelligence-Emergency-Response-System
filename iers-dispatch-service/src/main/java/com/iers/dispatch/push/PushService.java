package com.iers.dispatch.push;

import com.iers.dispatch.dto.feign.InvalidateTokensRequest;
import com.iers.dispatch.feign.AuthServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sends one push to every phone of one user.
 * Tokens come from Auth. A token that FCM calls dead is reported back to Auth so it is removed.
 * Never throws: a push problem must not break the rescue.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushService {

    private final AuthServiceClient authClient;
    private final PushSender sender;

    /** @return how many phones accepted the message */
    public int sendToUser(UUID userId, PushMessage message) {
        List<String> tokens;
        try {
            tokens = authClient.getPushTokens(userId);
        } catch (RuntimeException e) {
            log.warn("Could not read push tokens of user {}: {}", userId, e.getMessage());
            return 0;
        }
        if (tokens == null || tokens.isEmpty()) {
            log.info("User {} has no push token; {} not sent", userId, message.data().get("type"));
            return 0;
        }

        int sent = 0;
        List<String> dead = new ArrayList<>();
        for (String token : tokens) {
            PushOutcome outcome = sender.send(token, message);
            if (outcome == PushOutcome.SENT) {
                sent++;
            } else if (outcome == PushOutcome.DEAD_TOKEN) {
                dead.add(token);
            }
        }

        if (!dead.isEmpty()) {
            try {
                authClient.invalidatePushTokens(new InvalidateTokensRequest(dead));
            } catch (RuntimeException e) {
                log.warn("Could not tell Auth about {} dead push token(s): {}", dead.size(), e.getMessage());
            }
        }
        log.info("Push {} to user {}: {}/{} phone(s)", message.data().get("type"), userId, sent, tokens.size());
        return sent;
    }
}
