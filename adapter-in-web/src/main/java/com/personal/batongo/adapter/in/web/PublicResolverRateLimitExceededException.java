package com.personal.batongo.adapter.in.web;

public final class PublicResolverRateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public PublicResolverRateLimitExceededException(long retryAfterSeconds) {
        super(null, null, false, false);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
