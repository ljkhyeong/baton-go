package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.InvalidCreationTimeException;
import java.time.Instant;

public final class CreationTimeStoragePolicy {

    public static final Instant MINIMUM = Instant.parse("1000-01-01T00:00:00Z");
    public static final Instant MAXIMUM =
            Instant.parse("9999-12-31T23:59:59.999999Z");

    private static final int NANOS_PER_MICROSECOND = 1_000;

    private CreationTimeStoragePolicy() {
    }

    public static void requireStorable(Instant notBefore, Instant expiresAt) {
        if (!isStorable(notBefore, expiresAt)) {
            throw new InvalidCreationTimeException();
        }
    }

    public static boolean isStorable(Instant notBefore, Instant expiresAt) {
        return isStorable(notBefore) && isStorable(expiresAt);
    }

    public static boolean isWithinRange(Instant notBefore, Instant expiresAt) {
        return isWithinRange(notBefore) && isWithinRange(expiresAt);
    }

    private static boolean isStorable(Instant value) {
        return isWithinRange(value)
                && (value == null || value.getNano() % NANOS_PER_MICROSECOND == 0);
    }

    private static boolean isWithinRange(Instant value) {
        return value == null || !value.isBefore(MINIMUM) && !value.isAfter(MAXIMUM);
    }
}
