package com.iers.dispatch.sms;

/**
 * Sends one text message. The rest of the service only knows this interface, so the
 * provider (SimGate, Twilio, a local gateway) can be changed without touching alert code.
 */
public interface SmsSender {

    /**
     * @param toE164  phone number in international form, e.g. +923001234567
     * @param message the text
     * @return the result; implementations must not throw for ordinary delivery problems
     */
    SmsResult send(String toE164, String message);
}
