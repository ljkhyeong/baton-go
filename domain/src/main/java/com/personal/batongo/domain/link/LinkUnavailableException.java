package com.personal.batongo.domain.link;

public class LinkUnavailableException extends RuntimeException {

    private final Reason reason;

    public LinkUnavailableException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        NOT_ACTIVE,
        EXPIRED,
        REVOKED
    }
}
