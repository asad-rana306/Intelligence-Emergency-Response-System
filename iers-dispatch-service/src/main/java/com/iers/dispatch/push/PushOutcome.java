package com.iers.dispatch.push;

/** What happened to one push sent to one device token. */
public enum PushOutcome {
    /** FCM accepted the message. */
    SENT,
    /** The token is no longer valid (app removed, token rotated). It should be deleted. */
    DEAD_TOKEN,
    /** Any other problem. The token is kept. */
    FAILED
}
