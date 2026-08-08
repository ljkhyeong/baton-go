package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.InvalidIdempotencyKeyException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class CreationIdempotencyKey {

    private static final Pattern CURRENT_CANONICAL_UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}"
                    + "-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    );

    private final String value;
    private final boolean currentContract;

    public CreationIdempotencyKey(String value) {
        this(requireCurrentContract(value), true);
    }

    private CreationIdempotencyKey(String value, boolean currentContract) {
        this.value = value;
        this.currentContract = currentContract;
    }

    public static CreationIdempotencyKey parseRequest(String value) {
        if (matchesCurrentContract(value)) {
            return new CreationIdempotencyKey(value, true);
        }

        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidIdempotencyKeyException();
        }
        if (!parsed.toString().equalsIgnoreCase(value)) {
            throw new InvalidIdempotencyKeyException();
        }
        return new CreationIdempotencyKey(parsed.toString(), false);
    }

    public String value() {
        return value;
    }

    public boolean meetsCurrentContract() {
        return currentContract;
    }

    private static String requireCurrentContract(String value) {
        if (!matchesCurrentContract(value)) {
            throw new InvalidIdempotencyKeyException();
        }
        return value;
    }

    private static boolean matchesCurrentContract(String value) {
        return value != null && CURRENT_CANONICAL_UUID.matcher(value).matches();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof CreationIdempotencyKey that
                && value.equals(that.value)
                && currentContract == that.currentContract;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, currentContract);
    }

    @Override
    public String toString() {
        return "CreationIdempotencyKey[redacted]";
    }
}
