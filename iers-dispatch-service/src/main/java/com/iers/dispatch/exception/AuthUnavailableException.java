package com.iers.dispatch.exception;

/**
 * Auth could not answer (down, too slow, circuit open). Thrown by the Feign fallback for the
 * calls where "no answer" must not look like "empty answer": the contact list and the
 * list of available responders.
 */
public class AuthUnavailableException extends RuntimeException {

    public AuthUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
