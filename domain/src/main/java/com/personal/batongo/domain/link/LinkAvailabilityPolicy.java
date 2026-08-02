package com.personal.batongo.domain.link;

import java.time.Instant;
import java.util.Objects;

public final class LinkAvailabilityPolicy {

    private LinkAvailabilityPolicy() {
    }

    public static void requireResolvableAt(
            Instant revokedAt,
            Instant notBefore,
            Instant expiresAt,
            Instant now
    ) {
        Objects.requireNonNull(now, "판정 시각은 필수입니다");
        if (revokedAt != null) {
            throw new LinkUnavailableException(
                    LinkUnavailableException.Reason.REVOKED,
                    "폐기된 링크입니다"
            );
        }
        if (notBefore != null && now.isBefore(notBefore)) {
            throw new LinkUnavailableException(
                    LinkUnavailableException.Reason.NOT_ACTIVE,
                    "아직 사용할 수 없는 링크입니다"
            );
        }
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            throw new LinkUnavailableException(
                    LinkUnavailableException.Reason.EXPIRED,
                    "만료된 링크입니다"
            );
        }
    }
}
