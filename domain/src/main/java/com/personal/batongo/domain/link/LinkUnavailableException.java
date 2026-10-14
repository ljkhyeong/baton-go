package com.personal.batongo.domain.link;

import com.personal.batongo.domain.link.LinkAvailabilityPolicy.Status;
import java.time.Duration;
import java.time.Instant;

public class LinkUnavailableException extends RuntimeException {

    private final Status status;
    private final Instant notBefore;
    private final Duration retiredFor;

    public LinkUnavailableException(Status status, String message, Instant notBefore, Duration retiredFor) {
        super(message);
        this.status = status;
        this.notBefore = notBefore;
        this.retiredFor = retiredFor;
    }

    public Status status() {
        return status;
    }

    public Instant notBefore() {
        return notBefore;
    }

    /** 만료·폐기 링크가 판정 시각까지 종료된 채 지난 시간이다. 활성 전 링크는 null이다. */
    public Duration retiredFor() {
        return retiredFor;
    }
}
