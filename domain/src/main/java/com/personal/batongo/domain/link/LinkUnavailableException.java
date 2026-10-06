package com.personal.batongo.domain.link;

import com.personal.batongo.domain.link.LinkAvailabilityPolicy.Status;
import java.time.Instant;

public class LinkUnavailableException extends RuntimeException {

    private final Status status;
    private final Instant notBefore;

    public LinkUnavailableException(Status status, String message, Instant notBefore) {
        super(message);
        this.status = status;
        this.notBefore = notBefore;
    }

    public Status status() {
        return status;
    }

    public Instant notBefore() {
        return notBefore;
    }
}
