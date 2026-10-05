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

    Optional<StoredLinkReplay> findReplayById(UUID id);

    Optional<StoredLinkResolution> findResolutionByCodeHash(String codeHash);

    Optional<StoredLinkSnapshot> findStoredById(UUID id);

    List<StoredLinkSnapshot> findStoredByIds(List<UUID> ids);

    Optional<StoredLinkSnapshot> findStoredByIdForUpdate(UUID id);

    List<StoredLinkSnapshot> scanStoredAfter(UUID afterLinkId, int limit);

    void revokeStored(UUID id, Instant revokedAt);

    record StoredLinkReplay(
            UUID id,
            String targetSystem,
            String targetPath,
            String purpose,
            String codeHash,
            Instant notBefore,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt
    ) {
        @Override
        public String toString() {
            return "StoredLinkReplay[id=" + id + "]";
        }
    }

    record StoredLinkResolution(
            UUID id,
            String targetSystem,
            String targetPath,
            String purpose,
            Instant notBefore,
            Instant expiresAt,
            Instant revokedAt
    ) {
        @Override
        public String toString() {
            return "StoredLinkResolution[id=" + id + "]";
        }
    }

    record StoredLinkSnapshot(
            UUID id,
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
            return "StoredLinkSnapshot[id=" + id + "]";
        }
    }
}
