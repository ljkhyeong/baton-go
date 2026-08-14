package com.personal.batongo.application.link.port.out;

import com.personal.batongo.domain.link.SmartLink;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SmartLinkRepository {

    void save(SmartLink smartLink);

    Optional<StoredLinkReplay> findReplayById(UUID id);

    Optional<StoredLinkResolution> findResolutionByCodeHash(String codeHash);

    Optional<StoredLinkSnapshot> findStoredById(UUID id);

    Optional<StoredLinkSnapshot> findStoredByIdForUpdate(UUID id);

    List<StoredLinkSnapshot> scanStoredAfter(UUID afterLinkId, int limit);

    boolean revokeStoredIfVersion(UUID id, long expectedVersion, Instant revokedAt);

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
            Instant createdAt,
            long version,
            boolean creationRequestPresent
    ) {
        @Override
        public String toString() {
            return "StoredLinkSnapshot[id=" + id + "]";
        }
    }
}
