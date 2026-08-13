package com.personal.batongo.application.link;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class CreationTimeStoragePolicy {

    public static final Instant MINIMUM = Instant.parse("1582-10-15T00:00:00Z");
    public static final Instant MAXIMUM =
            Instant.parse("9999-12-31T23:59:59.999999Z");

    private CreationTimeStoragePolicy() {
    }

    public static boolean isWithinRange(Instant notBefore, Instant expiresAt) {
        return isWithinRange(notBefore) && isWithinRange(expiresAt);
    }

    static boolean hasMicrosecondPrecision(Instant notBefore, Instant expiresAt) {
        return hasMicrosecondPrecision(notBefore) && hasMicrosecondPrecision(expiresAt);
    }

    private static boolean hasMicrosecondPrecision(Instant value) {
        return value == null || value.equals(value.truncatedTo(ChronoUnit.MICROS));
    }

    private static boolean isWithinRange(Instant value) {
        return value == null || !value.isBefore(MINIMUM) && !value.isAfter(MAXIMUM);
    }
}
