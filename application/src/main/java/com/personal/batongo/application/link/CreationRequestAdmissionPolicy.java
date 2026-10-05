package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.InvalidRequestException;
import java.time.Instant;

final class CreationRequestAdmissionPolicy {

    private static final Instant MINIMUM = Instant.parse("1582-10-15T00:00:00Z");
    private static final Instant MAXIMUM =
            Instant.parse("9999-12-31T23:59:59.999999Z");

    private CreationRequestAdmissionPolicy() {
    }

    /** MySQL DATETIME(6)에 그대로 저장되는 시각만 받아 재시도 비교가 저장값과 어긋나지 않게 한다. */
    static void requireStorableTimes(Instant notBefore, Instant expiresAt) {
        if (!isStorable(notBefore) || !isStorable(expiresAt)) {
            throw InvalidRequestException.creationTime();
        }
    }

    private static boolean isStorable(Instant value) {
        return value == null
                || !value.isBefore(MINIMUM) && !value.isAfter(MAXIMUM) && value.getNano() % 1_000 == 0;
    }
}
