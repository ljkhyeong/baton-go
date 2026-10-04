package com.personal.batongo.domain.link;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 새로 저장할 링크의 필수 값과 생성·활성·만료 시각 순서를 검증합니다. */
public record SmartLink(
        UUID id,
        String codeHash,
        TrustedTarget trustedTarget,
        Instant notBefore,
        Instant expiresAt,
        Instant createdAt
) {

    public SmartLink {
        Objects.requireNonNull(id, "링크 식별자는 필수입니다");
        Objects.requireNonNull(codeHash, "링크 코드 해시는 필수입니다");
        Objects.requireNonNull(trustedTarget, "신뢰 대상은 필수입니다");
        Objects.requireNonNull(createdAt, "생성 시각은 필수입니다");
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new LinkValidationException("만료 시각은 생성 시각보다 뒤여야 합니다");
        }
        if (notBefore != null && expiresAt != null && !expiresAt.isAfter(notBefore)) {
            throw new LinkValidationException("만료 시각은 활성 시작 시각보다 뒤여야 합니다");
        }
    }

    @Override
    public String toString() {
        return "SmartLink[id=" + id + ", target=redacted]";
    }
}
