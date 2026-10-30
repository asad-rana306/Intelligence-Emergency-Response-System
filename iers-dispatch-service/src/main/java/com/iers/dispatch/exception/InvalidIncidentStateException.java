package com.iers.dispatch.exception;

/** The incident is in the wrong state for this action. Answered with HTTP 409. */
public class InvalidIncidentStateException extends RuntimeException {
    public InvalidIncidentStateException(String message) { super(message); }
}
