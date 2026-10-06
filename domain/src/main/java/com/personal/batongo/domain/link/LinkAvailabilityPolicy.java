package com.personal.batongo.domain.link;

import java.time.Instant;
import java.util.Objects;

public final class LinkAvailabilityPolicy {

    public enum Status {
        NOT_ACTIVE, ACTIVE, EXPIRED, REVOKED
    }

    private LinkAvailabilityPolicy() {
    }

    public static void requireResolvableAt(
            Instant revokedAt,
            Instant notBefore,
            Instant expiresAt,
            Instant now
    ) {
        Status status = evaluate(revokedAt, notBefore, expiresAt, now);
        switch (status) {
            case ACTIVE -> { }
            case NOT_ACTIVE -> throw new LinkUnavailableException(status, "아직 사용할 수 없는 링크입니다", notBefore);
            case EXPIRED -> throw new LinkUnavailableException(status, "만료된 링크입니다", null);
            case REVOKED -> throw new LinkUnavailableException(status, "폐기된 링크입니다", null);
        }
    }

    public static Status evaluate(
            Instant revokedAt,
            Instant notBefore,
            Instant expiresAt,
            Instant now
    ) {
        Objects.requireNonNull(now, "판정 시각은 필수입니다");
        if (revokedAt != null) {
            return Status.REVOKED;
        }
        if (notBefore != null && now.isBefore(notBefore)) {
            return Status.NOT_ACTIVE;
        }
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            return Status.EXPIRED;
        }
        return Status.ACTIVE;
    }
}
