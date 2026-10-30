package com.iers.realtime.dto;

/** The call set-up messages. The audio never touches the server. */
public enum CallKind {
    RING, ACCEPT, DECLINE, OFFER, ANSWER, ICE, HANGUP;

    /** Case-insensitive; null when the text is missing or not a known kind. */
    public static CallKind parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return CallKind.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
