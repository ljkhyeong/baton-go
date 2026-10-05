package com.personal.batongo.application.link.port.out;

import com.personal.batongo.domain.link.SmartLink;
import com.personal.batongo.domain.link.TrustedTarget;
import com.personal.batongo.domain.link.TrustedTargetPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SmartLinkRepository {

    void save(SmartLink smartLink);

    Optional<StoredLink> findById(UUID id);

    Optional<StoredLink> findByIdForUpdate(UUID id);

    Optional<StoredLink> findByCodeHash(String codeHash);

    List<StoredLink> findByIds(List<UUID> ids);

    List<StoredLink> scanAfter(UUID afterLinkId, int limit);

    void revoke(UUID id, Instant revokedAt);

    /** 저장된 링크 원문이다. 알 수 없는 대상 시스템·목적도 404로 숨길 수 있도록 문자열로 읽는다. */
    record StoredLink(
            UUID id,
            String codeHash,
            String targetSystem,
            String targetPath,
            String purpose,
            Instant notBefore,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt
    ) {
        /** v1 계약에 맞는 저장 대상만 신뢰 대상으로 바꾼다. */
        public Optional<TrustedTarget> trustedTarget() {
            return TrustedTargetPolicy.findAllowed(targetSystem, purpose, targetPath);
        }

        @Override
        public String toString() {
            return "StoredLink[id=" + id + "]";
        }
    }
}
