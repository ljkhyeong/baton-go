package com.personal.batongo.domain.link;

import java.time.Instant;

public class LinkUnavailableException extends RuntimeException {

    private final Reason reason;
    private final Instant notBefore;

    public LinkUnavailableException(Reason reason, String message) {
        this(reason, message, null);
    }

    public LinkUnavailableException(Reason reason, String message, Instant notBefore) {
        super(message);
        this.reason = reason;
        this.notBefore = notBefore;
    }

    public Reason reason() {
        return reason;
    }

    public Instant notBefore() {
        return notBefore;
    }

    public enum Reason {
        NOT_ACTIVE,
        EXPIRED,
        REVOKED
    }
}
