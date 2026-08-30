package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.InvalidIdempotencyKeyException;
import com.personal.batongo.application.link.error.InvalidRequestException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

final class CreationRequestAdmissionPolicy {

    private static final Instant MINIMUM = Instant.parse("1582-10-15T00:00:00Z");
    private static final Instant MAXIMUM =
            Instant.parse("9999-12-31T23:59:59.999999Z");

    private CreationRequestAdmissionPolicy() {
    }

    static Decision evaluate(
            CreationIdempotencyKey idempotencyKey,
            Instant notBefore,
            Instant expiresAt
    ) {
        if (!isWithinRange(notBefore) || !isWithinRange(expiresAt)) {
            throw InvalidRequestException.creationTime();
        }

        Instant storedNotBefore = databaseTime(notBefore);
        Instant storedExpiresAt = databaseTime(expiresAt);
        if (!idempotencyKey.allowsNewReservation()) {
            return new Decision(
                    storedNotBefore,
                    storedExpiresAt,
                    ReplayOnlyReason.LEGACY_IDEMPOTENCY_KEY
            );
        }
        if (!Objects.equals(notBefore, storedNotBefore)
                || !Objects.equals(expiresAt, storedExpiresAt)) {
            return new Decision(
                    storedNotBefore,
                    storedExpiresAt,
                    ReplayOnlyReason.SUB_MICROSECOND_TIME
            );
        }
        return new Decision(storedNotBefore, storedExpiresAt, ReplayOnlyReason.NONE);
    }

    private static Instant databaseTime(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean isWithinRange(Instant value) {
        return value == null || !value.isBefore(MINIMUM) && !value.isAfter(MAXIMUM);
    }

    enum ReplayOnlyReason {
        NONE,
        LEGACY_IDEMPOTENCY_KEY,
        SUB_MICROSECOND_TIME
    }

    record Decision(
            Instant notBefore,
            Instant expiresAt,
            ReplayOnlyReason replayOnlyReason
    ) {
        boolean allowsNewReservation() {
            return replayOnlyReason == ReplayOnlyReason.NONE;
        }

        RuntimeException missingReservationException() {
            return switch (replayOnlyReason) {
                case LEGACY_IDEMPOTENCY_KEY -> new InvalidIdempotencyKeyException();
                case SUB_MICROSECOND_TIME -> InvalidRequestException.creationTime();
                case NONE -> new IllegalStateException(
                        "신규 생성 가능한 요청에는 기존 예약이 필수일 수 없습니다"
                );
            };
        }
    }
}
