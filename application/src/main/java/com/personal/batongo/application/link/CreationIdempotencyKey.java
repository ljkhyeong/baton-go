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
        UUID parsed = requireCanonicalUuid(value);
        String canonicalValue = parsed.toString();
        if (canonicalValue.equals(value) && matchesCurrentContract(parsed)) {
            return new CreationIdempotencyKey(
                    canonicalValue,
                    true
            );
        }
        return new CreationIdempotencyKey(
                canonicalValue,
                false
        );
    }

    public String value() {
        return value;
    }

    public boolean allowsNewReservation() {
        return allowsNewReservation;
    }

    private static UUID requireCanonicalUuid(String value) {
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidIdempotencyKeyException();
        }
        if (!parsed.toString().equalsIgnoreCase(value)) {
            throw new InvalidIdempotencyKeyException();
        }
        return parsed;
    }

    private static boolean matchesCurrentContract(UUID value) {
        return value.version() >= 1
                && value.version() <= 5
                && value.variant() == 2;
    }

    @Override
    public String toString() {
        return "CreationIdempotencyKey[redacted]";
    }
}
