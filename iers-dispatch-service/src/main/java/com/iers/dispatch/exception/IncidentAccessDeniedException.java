package com.iers.dispatch.exception;

/** The caller is the wrong person for this incident. Answered with HTTP 403. */
public class IncidentAccessDeniedException extends RuntimeException {
    public IncidentAccessDeniedException(String message) { super(message); }
}
