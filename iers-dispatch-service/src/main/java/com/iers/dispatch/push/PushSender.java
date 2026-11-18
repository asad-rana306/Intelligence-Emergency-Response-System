package com.iers.dispatch.push;

/**
 * Sends one push to one device token. Implementations must not throw for ordinary
 * delivery problems; they answer with a {@link PushOutcome}.
 */
public interface PushSender {

    PushOutcome send(String token, PushMessage message);
}
