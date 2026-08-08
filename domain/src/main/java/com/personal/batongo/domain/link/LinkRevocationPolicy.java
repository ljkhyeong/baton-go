package com.personal.batongo.domain.link;

import java.time.Instant;
import java.util.Objects;

public final class LinkRevocationPolicy {

    private LinkRevocationPolicy() {
    }

    public static Instant requireFirstRevocationAt(
            Instant createdAt,
            Instant requestedRevokedAt
    ) {
        Objects.requireNonNull(createdAt, "생성 시각은 필수입니다");
        Objects.requireNonNull(requestedRevokedAt, "폐기 시각은 필수입니다");
        if (requestedRevokedAt.isBefore(createdAt)) {
            throw new IllegalStateException("폐기 시각은 생성 시각보다 빠를 수 없습니다");
        }
        return requestedRevokedAt;
    }
}
