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
        if (matchesCurrentContract(value)) {
            return new CreationIdempotencyKey(
                    value,
                    ReservationAdmission.CREATE_OR_REPLAY
            );
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
        return new CreationIdempotencyKey(
                parsed.toString(),
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
