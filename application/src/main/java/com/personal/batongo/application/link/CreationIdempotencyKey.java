package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.InvalidIdempotencyKeyException;
import java.util.UUID;

public final class CreationIdempotencyKey {

    private final String value;
    private final boolean allowsNewReservation;

    private CreationIdempotencyKey(
            String value,
            boolean allowsNewReservation
    ) {
        this.value = value;
        this.allowsNewReservation = allowsNewReservation;
    }

    public static CreationIdempotencyKey parseRequest(String value) {
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidIdempotencyKeyException();
        }
        String canonicalValue = parsed.toString();
        if (!canonicalValue.equalsIgnoreCase(value)) {
            throw new InvalidIdempotencyKeyException();
        }
        return new CreationIdempotencyKey(
                canonicalValue,
                canonicalValue.equals(value)
                        && parsed.version() >= 1
                        && parsed.version() <= 5
                        && parsed.variant() == 2
        );
    }

    public String value() {
        return value;
    }

    boolean allowsNewReservation() {
        return allowsNewReservation;
    }

    @Override
    public String toString() {
        return "CreationIdempotencyKey[redacted]";
    }
}
