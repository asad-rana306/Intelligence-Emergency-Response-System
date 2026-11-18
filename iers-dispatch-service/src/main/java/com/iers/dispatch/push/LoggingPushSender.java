package com.iers.dispatch.push;

import lombok.extern.slf4j.Slf4j;

/** Used when Firebase is switched off or not configured: the push is only written to the log. */
@Slf4j
public class LoggingPushSender implements PushSender {

    @Override
    public PushOutcome send(String token, PushMessage message) {
        log.info("[push not sent - Firebase off] type={} title={}", message.data().get("type"), message.title());
        return PushOutcome.SENT;
    }
}
