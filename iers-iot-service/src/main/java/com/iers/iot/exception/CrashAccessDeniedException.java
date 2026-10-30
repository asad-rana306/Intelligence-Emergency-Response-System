package com.iers.iot.exception;

/** The logged-in user is not the driver this crash belongs to. Becomes HTTP 403. */
public class CrashAccessDeniedException extends RuntimeException {
    public CrashAccessDeniedException(String message) { super(message); }
}