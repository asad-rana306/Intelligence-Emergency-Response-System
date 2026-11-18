package com.iers.dispatch.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development sender: nothing leaves the computer. The text is
 * written to the log, so you can test without spending SMS credit.
 * Active when dispatch.sms.provider=log (the default). NEVER use it in production.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "dispatch.sms", name = "provider", havingValue = "log", matchIfMissing = true)
public class LoggingSmsSender implements SmsSender {

    public LoggingSmsSender() {
        log.warn("SMS provider is 'log': messages are only printed, NOT sent. "
                + "Set dispatch.sms.provider=simgate for real SMS.");
    }

    @Override
    public SmsResult send(String toE164, String message) {
        log.info("[SMS not sent - log provider] to={} text={}", toE164, message);
        return SmsResult.sent();
    }
}
