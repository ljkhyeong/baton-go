package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.InvalidCreationTimeException;
import com.personal.batongo.application.link.error.InvalidIdempotencyKeyException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

final class CreationRequestAdmissionPolicy {

    private CreationRequestAdmissionPolicy() {
    }

    static Decision evaluate(
            CreationIdempotencyKey idempotencyKey,
            Instant notBefore,
            Instant expiresAt
    ) {
        if (!CreationTimeStoragePolicy.isWithinRange(notBefore, expiresAt)) {
            throw new InvalidCreationTimeException();
        }

        Instant storedNotBefore = databaseTime(notBefore);
        Instant storedExpiresAt = databaseTime(expiresAt);
        if (!idempotencyKey.allowsNewReservation()) {
            return Decision.replayOnly(
                    storedNotBefore,
                    storedExpiresAt,
                    ReplayOnlyReason.LEGACY_IDEMPOTENCY_KEY
            );
        }
        if (!CreationTimeStoragePolicy.isStorable(notBefore, expiresAt)) {
            return Decision.replayOnly(
                    storedNotBefore,
                    storedExpiresAt,
                    ReplayOnlyReason.SUB_MICROSECOND_TIME
            );
        }
        return Decision.createOrReplay(storedNotBefore, storedExpiresAt);
    }

    private static Instant databaseTime(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
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

        private static Decision createOrReplay(
                Instant notBefore,
                Instant expiresAt
        ) {
            return new Decision(notBefore, expiresAt, ReplayOnlyReason.NONE);
        }

        private static Decision replayOnly(
                Instant notBefore,
                Instant expiresAt,
                ReplayOnlyReason reason
        ) {
            return new Decision(notBefore, expiresAt, reason);
        }

        boolean allowsNewReservation() {
            return replayOnlyReason == ReplayOnlyReason.NONE;
        }

        RuntimeException missingReservationException() {
            return switch (replayOnlyReason) {
                case LEGACY_IDEMPOTENCY_KEY -> new InvalidIdempotencyKeyException();
                case SUB_MICROSECOND_TIME -> new InvalidCreationTimeException();
                case NONE -> new IllegalStateException(
                        "신규 생성 가능한 요청에는 기존 예약이 필수일 수 없습니다"
                );
            };
        }

        @Override
        public String toString() {
            return "CreationRequestAdmissionPolicy.Decision[redacted]";
        }
    }
}
