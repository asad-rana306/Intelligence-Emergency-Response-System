package com.iers.auth.exception;

/** The phone number was not verified with an SMS code (the API answers 403). */
public class PhoneNotVerifiedException extends RuntimeException {
    public PhoneNotVerifiedException(String message) {
        super(message);
    }
}
