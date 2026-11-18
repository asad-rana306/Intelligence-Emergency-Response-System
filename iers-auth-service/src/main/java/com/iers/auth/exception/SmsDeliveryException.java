package com.iers.auth.exception;

/** The SMS provider did not accept the message (the API answers 502). */
public class SmsDeliveryException extends RuntimeException {
    public SmsDeliveryException(String message) {
        super(message);
    }
}
