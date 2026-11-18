package com.iers.auth.sms;

/** What the SMS provider said about one message. */
public record SmsResult(Status status, String detail) {

    public enum Status {
        /** The phone/provider accepted the message for immediate delivery. */
        SENT,
        /** Accepted, but waiting (for example the gateway phone is offline and will send later). */
        QUEUED,
        /** Not accepted. */
        FAILED
    }

    public static SmsResult sent() {
        return new SmsResult(Status.SENT, "sent");
    }

    public static SmsResult queued() {
        return new SmsResult(Status.QUEUED, "queued");
    }

    public static SmsResult failed(String detail) {
        return new SmsResult(Status.FAILED, detail);
    }

    public boolean accepted() {
        return status != Status.FAILED;
    }
}
