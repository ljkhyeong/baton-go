package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.InvalidIdempotencyKeyException;
import java.util.Objects;
import java.util.UUID;

public final class CreationIdempotencyKey {

    private final String value;
    private final ReservationAdmission reservationAdmission;

    public CreationIdempotencyKey(String value) {
        this(requireCurrentContract(value), ReservationAdmission.CREATE_OR_REPLAY);
    }

    private CreationIdempotencyKey(
            String value,
            ReservationAdmission reservationAdmission
    ) {
        this.value = value;
        this.reservationAdmission = reservationAdmission;
    }

    public static CreationIdempotencyKey parseRequest(String value) {
        UUID parsed = requireCanonicalUuid(value, true);
        String canonicalValue = parsed.toString();
        if (canonicalValue.equals(value) && matchesCurrentContract(parsed)) {
            return new CreationIdempotencyKey(
                    canonicalValue,
                    ReservationAdmission.CREATE_OR_REPLAY
            );
        }
        return new CreationIdempotencyKey(
                canonicalValue,
                ReservationAdmission.REPLAY_ONLY
        );
    }

    public String value() {
        return value;
    }

    public boolean allowsNewReservation() {
        return reservationAdmission == ReservationAdmission.CREATE_OR_REPLAY;
    }

    private static String requireCurrentContract(String value) {
        UUID parsed = requireCanonicalUuid(value, false);
        if (!matchesCurrentContract(parsed)) {
            throw new InvalidIdempotencyKeyException();
        }
        return parsed.toString();
    }

    private static UUID requireCanonicalUuid(String value, boolean allowUppercase) {
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidIdempotencyKeyException();
        }
        boolean canonical = allowUppercase
                ? parsed.toString().equalsIgnoreCase(value)
                : parsed.toString().equals(value);
        if (!canonical) {
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
    public boolean equals(Object other) {
        return this == other
                || other instanceof CreationIdempotencyKey that
                && value.equals(that.value)
                && reservationAdmission == that.reservationAdmission;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, reservationAdmission);
    }

    @Override
    public String toString() {
        return "CreationIdempotencyKey[redacted]";
    }

    private enum ReservationAdmission {
        CREATE_OR_REPLAY,
        REPLAY_ONLY
    }
}
